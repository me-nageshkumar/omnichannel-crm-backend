package me.nageshk.omnichannel.app.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import me.nageshk.omnichannel.ai.api.AiGateway;
import me.nageshk.omnichannel.ai.api.AiUsage;
import me.nageshk.omnichannel.ai.api.TurnRequest;
import me.nageshk.omnichannel.ai.api.TurnResult;
import me.nageshk.omnichannel.ai.api.TurnStatus;
import me.nageshk.omnichannel.ai.call.AiCall;
import me.nageshk.omnichannel.ai.call.AiTurnWorker;
import me.nageshk.omnichannel.app.support.OpenAiStub;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import tools.jackson.databind.JsonNode;

/** The real OpenAI adapter against the local stub: one conversation over three turns, chained by response id. */
class OpenAiChainIT extends AiTestBase {

    private static final String KEY = "sk-chain-test-1234567890";

    @Autowired AiGateway gateway;
    @Autowired AiTurnWorker worker;

    private static String text(String id, String text, int in, int out) {
        return "{\"id\":\"" + id + "\",\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\""
                + text + "\"}]}],\"usage\":{\"input_tokens\":" + in + ",\"output_tokens\":" + out + "}}";
    }

    private TurnResult turn(String profileId, String conversationId, String input) throws Exception {
        String id = gateway.submit(new TurnRequest(brand.getId(), "run1", "bot1", "Ask", profileId, null,
                "The customer is Asha.", List.of(input), List.of(), List.of(), false, false, null, conversationId,
                null));
        worker.tick().get(10, TimeUnit.SECONDS);
        return gateway.result(brand.getId(), id).orElseThrow();
    }

    private List<JsonNode> responseBodies() {
        return OPENAI.requests().stream().filter(r -> r.path().equals("/v1/responses"))
                .map(OpenAiStub.Recorded::body).map(mapper::readTree).toList();
    }

    @Test
    void threeTurnsChainOnPreviousResponse() throws Exception {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("name", "OpenAI");
        c.put("provider", "openai");
        c.put("apiKey", KEY);
        String connectionId = ai(HttpMethod.POST, "/connections", manager, c).getBody().get("id").asString();
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("name", "Sales");
        p.put("connectionId", connectionId);
        p.put("model", "gpt-5");
        p.put("instructions", "Be brief.");
        String profileId = ai(HttpMethod.POST, "/profiles", manager, p).getBody().get("id").asString();
        OPENAI.reset();
        OPENAI.enqueue(200, text("resp_1", "Hello Asha", 10, 5));
        OPENAI.enqueue(200, text("resp_2", "Sure", 20, 6));
        OPENAI.enqueue(200, "{\"id\":\"resp_3a\",\"output\":[{\"type\":\"function_call\",\"call_id\":\"call_9\","
                + "\"name\":\"crm__gone\",\"arguments\":\"{}\"}],\"usage\":{\"input_tokens\":30,\"output_tokens\":7}}");
        OPENAI.enqueue(200, text("resp_3b", "Done", 40, 8));

        TurnResult first = turn(profileId, null, "Hi");
        TurnResult second = turn(profileId, first.conversationId(), "Next");
        TurnResult third = turn(profileId, first.conversationId(), "Last");

        assertThat(List.of(first.status(), second.status(), third.status())).containsOnly(TurnStatus.DONE);
        assertThat(List.of(first.text(), second.text(), third.text())).containsExactly("Hello Asha", "Sure", "Done");
        assertThat(second.conversationId()).isEqualTo(first.conversationId());
        assertThat(third.conversationId()).isEqualTo(first.conversationId());

        List<JsonNode> bodies = responseBodies();
        assertThat(bodies).hasSize(4);
        assertThat(OPENAI.requests()).allSatisfy(r -> assertThat(r.authorization()).isEqualTo("Bearer " + KEY));
        for (JsonNode b : bodies) {
            assertThat(b.path("model").asString()).isEqualTo("gpt-5");
            assertThat(b.path("instructions").asString()).isEqualTo("Be brief.\n\nThe customer is Asha.");
            assertThat(b.path("metadata").path("runId").asString()).isEqualTo("run1");
        }
        assertThat(bodies.get(0).has("previous_response_id")).isFalse();
        assertThat(bodies.get(0).path("input").toString()).isEqualTo("[{\"role\":\"user\",\"content\":\"Hi\"}]");
        assertThat(bodies.get(1).path("previous_response_id").asString()).isEqualTo("resp_1");
        assertThat(bodies.get(1).path("input").toString()).isEqualTo("[{\"role\":\"user\",\"content\":\"Next\"}]");
        assertThat(bodies.get(2).path("previous_response_id").asString()).isEqualTo("resp_2");
        assertThat(bodies.get(2).path("input").toString()).isEqualTo("[{\"role\":\"user\",\"content\":\"Last\"}]");
        assertThat(bodies.get(3).path("previous_response_id").asString()).isEqualTo("resp_3a");
        JsonNode toolResult = bodies.get(3).path("input").get(0);
        assertThat(toolResult.path("type").asString()).isEqualTo("function_call_output");
        assertThat(toolResult.path("call_id").asString()).isEqualTo("call_9");
        assertThat(toolResult.path("output").asString()).startsWith("error: ");
        assertThat(bodies.get(3).path("input")).hasSize(1);

        assertThat(mongo.findById(first.callId(), AiCall.class).getUsage()).isEqualTo(new AiUsage(10, 5, 0, 0));
        assertThat(mongo.findById(second.callId(), AiCall.class).getUsage()).isEqualTo(new AiUsage(20, 6, 0, 0));
        assertThat(mongo.findById(third.callId(), AiCall.class).getUsage()).as("summed over the tool round")
                .isEqualTo(new AiUsage(70, 15, 0, 0));
        assertThat(third.usage()).isEqualTo(new AiUsage(70, 15, 0, 0));
    }

    @Test
    void aTurnSendsTheConnectionsSettingsAndAnOldConnectionStillRuns() throws Exception {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("name", "OpenAI");
        c.put("provider", "openai");
        c.put("apiKey", KEY);
        c.put("settings", Map.of("organization", "org_1", "project", "proj_1"));
        String connectionId = ai(HttpMethod.POST, "/connections", manager, c).getBody().get("id").asString();
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("name", "Sales");
        p.put("connectionId", connectionId);
        p.put("model", "gpt-5");
        String profileId = ai(HttpMethod.POST, "/profiles", manager, p).getBody().get("id").asString();
        OPENAI.reset();
        OPENAI.enqueue(200, text("resp_1", "Hello", 10, 5));

        assertThat(turn(profileId, null, "Hi").status()).isEqualTo(TurnStatus.DONE);
        assertThat(OPENAI.last().path()).isEqualTo("/v1/responses");
        assertThat(OPENAI.last().organization()).isEqualTo("org_1");
        assertThat(OPENAI.last().project()).isEqualTo("proj_1");

        // a connection saved before settings existed has no such field at all
        mongo.updateFirst(new org.springframework.data.mongodb.core.query.Query(),
                new org.springframework.data.mongodb.core.query.Update().unset("settings"), "aiConnections");
        OPENAI.reset();
        OPENAI.enqueue(200, text("resp_2", "Again", 10, 5));

        assertThat(turn(profileId, null, "Hi").status()).isEqualTo(TurnStatus.DONE);
        assertThat(OPENAI.last().authorization()).isEqualTo("Bearer " + KEY);
        assertThat(OPENAI.last().organization()).isNull();
    }
}
