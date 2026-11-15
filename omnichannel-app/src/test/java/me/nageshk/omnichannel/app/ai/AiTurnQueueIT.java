package me.nageshk.omnichannel.app.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import me.nageshk.omnichannel.ai.api.AiGateway;
import me.nageshk.omnichannel.ai.api.AiTurnListener;
import me.nageshk.omnichannel.ai.api.AiUsage;
import me.nageshk.omnichannel.ai.api.TurnRequest;
import me.nageshk.omnichannel.ai.api.TurnResult;
import me.nageshk.omnichannel.ai.api.TurnStatus;
import me.nageshk.omnichannel.ai.call.AiCall;
import me.nageshk.omnichannel.ai.call.AiCallStatus;
import me.nageshk.omnichannel.ai.call.AiTurnQueue;
import me.nageshk.omnichannel.ai.call.AiTurnWorker;
import me.nageshk.omnichannel.ai.config.AiProperties;
import me.nageshk.omnichannel.ai.provider.ProviderReply;
import me.nageshk.omnichannel.common.api.NotFoundException;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/** The turn queue end to end: submit, claim with a lease, run on the AI pool, deliver to the listeners. */
@Import(AiTurnQueueIT.Listener.class)
class AiTurnQueueIT extends AiTestBase {

    private static final String KEY = "scripted-key-0123456789";
    private static final String NEW_KEY = "scripted-key-replaced-42";
    private static final String SECRET = "mcp-secret-value-98765";

    /** Records every delivered result; can be told to throw on the next calls. */
    static class Recorder implements AiTurnListener {
        final List<TurnResult> results = new CopyOnWriteArrayList<>();
        final AtomicInteger throwNext = new AtomicInteger();

        @Override
        public void turnFinished(TurnResult r) {
            results.add(r);
            if (throwNext.getAndUpdate(n -> Math.max(0, n - 1)) > 0) throw new IllegalStateException("listener down");
        }
    }

    @TestConfiguration
    static class Listener {
        @Bean
        Recorder aiTurnRecorder() {
            return new Recorder();
        }
    }

    @Autowired AiGateway gateway;
    @Autowired AiTurnWorker worker;
    @Autowired AiTurnQueue queue;
    @Autowired AiProperties props;
    @Autowired Recorder recorder;

    private String connectionId;
    private String profileId;

    @BeforeEach
    void setUpProfile() {
        recorder.results.clear();
        recorder.throwNext.set(0);
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("name", "Scripted");
        c.put("provider", "scripted");
        c.put("apiKey", KEY);
        connectionId = ai(HttpMethod.POST, "/connections", manager, c).getBody().get("id").asString();
        profileId = saveProfile(null, "scripted-1", "Be kind.", List.of()).getBody().get("id").asString();
    }

    private ResponseEntity<JsonNode> saveProfile(String id, String model, String instructions, List<Object> mcp) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("name", "Sales");
        p.put("connectionId", connectionId);
        p.put("model", model);
        p.put("instructions", instructions);
        p.put("mcpServers", mcp);
        ResponseEntity<JsonNode> res = id == null ? ai(HttpMethod.POST, "/profiles", manager, p)
                : ai(HttpMethod.PUT, "/profiles/" + id, manager, p);
        assertThat(res.getStatusCode().is2xxSuccessful()).as(String.valueOf(res.getBody())).isTrue();
        return res;
    }

    private TurnRequest request(String conversationId, String input) {
        return new TurnRequest(brand.getId(), "run1", "bot1", "Ask", profileId, null, null, List.of(input), List.of(),
                List.of(), false, null, conversationId, null);
    }

    private static ProviderReply text(String t) {
        return new ProviderReply(t, List.of(), Map.of(), new AiUsage(3, 2, 0, 0), null, false);
    }

    private void tick() throws Exception {
        worker.tick().get(10, TimeUnit.SECONDS);
    }

    private AiCall row(String id) {
        return mongo.findById(id, AiCall.class);
    }

    @Test
    void submitThenTickDelivers() throws Exception {
        scripted.reply(text("Hello"));

        String id = gateway.submit(request(null, "Hi"));

        AiCall pending = row(id);
        assertThat(pending.getStatus()).isEqualTo(AiCallStatus.PENDING);
        assertThat(pending.getProvider()).isEqualTo("scripted");
        assertThat(pending.getModel()).isEqualTo("scripted-1");
        assertThat(pending.getConversationId()).isNotNull();
        assertThat(gateway.result(brand.getId(), id)).isEmpty();

        tick();

        assertThat(recorder.results).hasSize(1);
        TurnResult r = recorder.results.get(0);
        assertThat(r.callId()).isEqualTo(id);
        assertThat(r.status()).isEqualTo(TurnStatus.DONE);
        assertThat(r.text()).isEqualTo("Hello");
        assertThat(r.conversationId()).isEqualTo(pending.getConversationId());
        assertThat(r.runId()).isEqualTo("run1");
        AiCall done = row(id);
        assertThat(done.getStatus()).isEqualTo(AiCallStatus.DONE);
        assertThat(done.getAttempts()).isEqualTo(1);
        assertThat(done.getDeliveredAt()).isNotNull();
        assertThat(done.getUsage()).isEqualTo(new AiUsage(3, 2, 0, 0));
        assertThat(gateway.result(brand.getId(), id)).contains(r);
        assertThat(gateway.result(other.getId(), id)).as("another brand").isEmpty();

        clock.advance(Duration.ofMinutes(5));
        tick();
        assertThat(recorder.results).as("delivered once").hasSize(1);
        assertThat(scripted.requests()).hasSize(1);
    }

    @Test
    void submitChecksTheProfile() {
        TurnRequest r = new TurnRequest(other.getId(), "run1", "bot1", "Ask", profileId, null, null, List.of("Hi"),
                List.of(), List.of(), false, null, null, null);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> gateway.submit(r))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void expiredLeaseIsRetriedOnce() throws Exception {
        String id = gateway.submit(request(null, "Hi"));
        AiCall crashed = queue.claim(clock.instant()).orElseThrow();
        assertThat(crashed.getId()).isEqualTo(id);
        assertThat(crashed.getLeaseUntil()).isEqualTo(clock.instant().plus(props.turnTimeout()).plusSeconds(30));
        tick();
        assertThat(recorder.results).as("a live lease is not claimed again").isEmpty();

        clock.advance(props.turnTimeout().plusSeconds(31));
        scripted.reply(text("Back"));
        tick();

        AiCall done = row(id);
        assertThat(done.getStatus()).isEqualTo(AiCallStatus.DONE);
        assertThat(done.getAttempts()).isEqualTo(2);
        assertThat(recorder.results).extracting(TurnResult::text).containsExactly("Back");
    }

    @Test
    void exhaustedAttemptsFailInterrupted() throws Exception {
        String id = gateway.submit(request(null, "Hi"));
        queue.claim(clock.instant()).orElseThrow();
        clock.advance(props.turnTimeout().plusSeconds(31));
        queue.claim(clock.instant()).orElseThrow();
        clock.advance(props.turnTimeout().plusSeconds(31));

        tick();

        AiCall failed = row(id);
        assertThat(failed.getStatus()).isEqualTo(AiCallStatus.FAILED);
        assertThat(failed.getErrorCode()).isEqualTo("INTERRUPTED");
        assertThat(failed.getDeliveredAt()).isNotNull();
        assertThat(recorder.results).extracting(TurnResult::status, TurnResult::errorCode)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(TurnStatus.FAILED, "INTERRUPTED"));
        assertThat(scripted.requests()).isEmpty();
    }

    @Test
    void undeliveredIsRedelivered() throws Exception {
        recorder.throwNext.set(1);
        scripted.reply(text("Hello"));
        String id = gateway.submit(request(null, "Hi"));

        tick();
        assertThat(recorder.results).hasSize(1);
        assertThat(row(id).getDeliveredAt()).isNull();

        tick();
        assertThat(recorder.results).as("not before redeliverAfter").hasSize(1);

        clock.advance(props.redeliverAfter().plusSeconds(1));
        tick();
        assertThat(recorder.results).hasSize(2);
        assertThat(recorder.results.get(1).text()).isEqualTo("Hello");
        assertThat(row(id).getDeliveredAt()).isNotNull();
        assertThat(scripted.requests()).as("the turn is not run again").hasSize(1);
    }

    @Test
    void replacedKeyUsedByQueuedTurn() throws Exception {
        scripted.reply(text("Hello"));
        String id = gateway.submit(request(null, "Hi"));
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("name", "Scripted");
        c.put("provider", "scripted");
        c.put("apiKey", NEW_KEY);
        assertThat(ai(HttpMethod.PUT, "/connections/" + connectionId, manager, c).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        tick();

        assertThat(row(id).getStatus()).isEqualTo(AiCallStatus.DONE);
        assertThat(scripted.keysSeen()).containsExactly(NEW_KEY);
    }

    @Test
    void profileEditBetweenTurnsUsesNewModel() throws Exception {
        scripted.reply(text("One"));
        scripted.reply(text("Two"));
        gateway.submit(request(null, "Hi"));
        tick();
        String conversationId = recorder.results.get(0).conversationId();

        saveProfile(profileId, "scripted-2", "Be brief.", List.of());
        String second = gateway.submit(request(conversationId, "Again"));
        assertThat(row(second).getModel()).isEqualTo("scripted-2");
        tick();

        assertThat(scripted.requests()).extracting(r -> r.model(), r -> r.instructions()).containsExactly(
                org.assertj.core.groups.Tuple.tuple("scripted-1", "Be kind."),
                org.assertj.core.groups.Tuple.tuple("scripted-2", "Be brief."));
        assertThat(recorder.results.get(1).status()).isEqualTo(TurnStatus.DONE);

        // the connection moves to another provider mid-conversation: the next turn fails cleanly
        OPENAI.enqueue(200, "{\"data\":[]}");
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("name", "Scripted");
        c.put("provider", "openai");
        c.put("apiKey", "sk-other-provider-key-1");
        assertThat(ai(HttpMethod.PUT, "/connections/" + connectionId, manager, c).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        String third = gateway.submit(request(conversationId, "And now?"));
        tick();

        assertThat(row(third).getStatus()).isEqualTo(AiCallStatus.FAILED);
        assertThat(row(third).getErrorCode()).isEqualTo("PROVIDER_CHANGED");
        assertThat(recorder.results.get(2).errorCode()).isEqualTo("PROVIDER_CHANGED");
        assertThat(OPENAI.requests()).noneMatch(r -> r.path().startsWith("/v1/responses"));
    }

    @Test
    void rowHoldsNoSecrets() throws Exception {
        scripted.reply(text("Hello"));
        String done = gateway.submit(request(null, "Hi"));
        tick();
        String conversationId = recorder.results.get(0).conversationId();

        Map<String, Object> server = new LinkedHashMap<>();
        server.put("label", "crm");
        server.put("url", "https://mcp.invalid/mcp");
        server.put("connectBy", "APP");
        server.put("auth", "BEARER");
        server.put("secret", SECRET);
        saveProfile(profileId, "scripted-1", "Be kind.", new ArrayList<>(List.of(server)));
        String failed = gateway.submit(request(conversationId, "Look me up"));
        tick();

        assertThat(row(done).getStatus()).isEqualTo(AiCallStatus.DONE);
        assertThat(row(failed).getStatus()).isEqualTo(AiCallStatus.FAILED);
        assertThat(row(failed).getErrorCode()).isEqualTo("MCP_ERROR");
        assertThat(row(failed).getError()).startsWith("MCP server crm: ");
        List<String> raw = new ArrayList<>();
        for (String name : List.of("aiCalls", "aiConversations")) {
            for (Document d : mongo.getCollection(name).find()) raw.add(d.toJson());
        }
        assertThat(raw).hasSize(3);
        assertThat(raw).allSatisfy(json -> assertThat(json).doesNotContain(KEY).doesNotContain(SECRET)
                .doesNotContain("Bearer"));
        assertThat(recorder.results).hasSize(2);
        assertThat(recorder.results).allSatisfy(r -> assertThat(String.valueOf(r)).doesNotContain(KEY)
                .doesNotContain(SECRET));
    }
}
