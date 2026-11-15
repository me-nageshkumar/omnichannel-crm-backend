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
import me.nageshk.omnichannel.ai.api.AiField;
import me.nageshk.omnichannel.ai.api.AiFieldType;
import me.nageshk.omnichannel.ai.api.AiOutcome;
import me.nageshk.omnichannel.ai.api.AiReplyFormat;
import me.nageshk.omnichannel.ai.provider.ProviderReply;
import me.nageshk.omnichannel.ai.provider.ToolCall;
import me.nageshk.omnichannel.ai.turn.AiTurnRunner;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
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
        final java.util.Set<String> poison = java.util.concurrent.ConcurrentHashMap.newKeySet();

        @Override
        public void turnFinished(TurnResult r) {
            results.add(r);
            if (poison.contains(r.callId())) throw new IllegalStateException("listener refuses " + r.callId());
            if (throwNext.getAndUpdate(n -> Math.max(0, n - 1)) > 0) throw new IllegalStateException("listener down");
        }

        long offers(String callId) {
            return results.stream().filter(r -> r.callId().equals(callId)).count();
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
    @Autowired AiTurnRunner runner;

    private String connectionId;
    private String profileId;

    @BeforeEach
    void setUpProfile() {
        recorder.results.clear();
        recorder.throwNext.set(0);
        recorder.poison.clear();
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

    @Test
    void staleAttemptCannotFinish() throws Exception {
        String id = gateway.submit(request(null, "Hi"));
        AiCall stale = queue.claim(clock.instant()).orElseThrow();
        clock.advance(props.turnTimeout().plusSeconds(31));
        AiCall live = queue.claim(clock.instant()).orElseThrow();
        assertThat(live.getAttempts()).isEqualTo(2);
        scripted.reply(text("From the stale copy"));

        TurnResult r = runner.run(stale);

        assertThat(r.errorCode()).isEqualTo("CLAIM_LOST");
        assertThat(queue.finish(stale)).isFalse();
        AiCall row = row(id);
        assertThat(row.getStatus()).isEqualTo(AiCallStatus.RUNNING);
        assertThat(row.getAttempts()).isEqualTo(2);
        assertThat(row.getReply()).isNull();
        assertThat(mongo.findById(row.getConversationId(), me.nageshk.omnichannel.ai.conversation.AiConversation.class)
                .getTurns()).as("a lost claim never writes the conversation").isZero();
        assertThat(recorder.results).isEmpty();
    }

    @Test
    void appliedTurnIsNotRunAgain() throws Exception {
        String id = gateway.submit(request(null, "Hi"));
        AiCall first = queue.claim(clock.instant()).orElseThrow();
        scripted.reply(text("Hello"));
        runner.run(first); // staged and applied to the conversation; the worker "dies" before finish

        clock.advance(props.turnTimeout().plusSeconds(31));
        tick();

        AiCall done = row(id);
        assertThat(done.getStatus()).isEqualTo(AiCallStatus.DONE);
        assertThat(done.getAttempts()).isEqualTo(2);
        assertThat(done.getReply()).isEqualTo("Hello");
        assertThat(scripted.requests()).as("the provider is asked once").hasSize(1);
        assertThat(recorder.results).extracting(TurnResult::text).containsExactly("Hello");
        assertThat(mongo.findById(done.getConversationId(), me.nageshk.omnichannel.ai.conversation.AiConversation.class)
                .getTurns()).isEqualTo(1);
    }

    @Test
    void failingListenerIsReofferedAtMostEveryRedeliverAfter() throws Exception {
        scripted.reply(text("Hello"));
        String id = gateway.submit(request(null, "Hi"));
        recorder.poison.add(id);

        tick();
        tick();
        assertThat(recorder.offers(id)).isEqualTo(1);
        clock.advance(props.redeliverAfter().plusSeconds(1));
        tick();
        tick();
        assertThat(recorder.offers(id)).isEqualTo(2);
        clock.advance(props.redeliverAfter().dividedBy(2));
        tick();
        assertThat(recorder.offers(id)).isEqualTo(2);
        clock.advance(props.redeliverAfter().dividedBy(2).plusSeconds(1));
        tick();
        assertThat(recorder.offers(id)).isEqualTo(3);
        assertThat(row(id).getDeliveryAttempts()).isEqualTo(2);

        // after the cap the row is left undelivered (visible) and no longer offered
        mongo.updateFirst(Query.query(Criteria.where("_id").is(id)),
                new Update().set("deliveryAttempts", me.nageshk.omnichannel.ai.call.AiTurnQueue.MAX_DELIVERY_ATTEMPTS),
                AiCall.class);
        clock.advance(props.redeliverAfter().plusSeconds(1));
        tick();
        assertThat(recorder.offers(id)).isEqualTo(3);
        assertThat(row(id).getDeliveredAt()).isNull();
    }

    @Test
    void poisonRowsDoNotBlockANewerRow() throws Exception {
        List<AiCall> poison = new ArrayList<>();
        for (int i = 0; i < 110; i++) {
            AiCall c = AiCall.pending(request(null, "old " + i), "conv-old", "scripted", "scripted-1",
                    clock.instant().minusSeconds(600));
            c.setStatus(AiCallStatus.DONE);
            c.setReply("old");
            c.setFinishedAt(clock.instant().minusSeconds(600));
            c.setNextDeliveryAt(clock.instant().minusSeconds(590));
            poison.add(c);
        }
        mongo.insertAll(poison);
        poison.forEach(c -> recorder.poison.add(c.getId()));
        recorder.throwNext.set(0);
        scripted.reply(text("New"));
        String id = gateway.submit(request(null, "Hi"));
        recorder.poison.add(id);
        tick(); // runs the new turn; its first delivery fails, like the old ones
        recorder.poison.remove(id);

        clock.advance(props.redeliverAfter().plusSeconds(1));
        for (int i = 0; i < 30 && row(id).getDeliveredAt() == null; i++) tick();

        assertThat(row(id).getDeliveredAt()).as("the newer row got its turn").isNotNull();
        assertThat(recorder.offers(id)).isEqualTo(2);
        assertThat(poison).allSatisfy(c -> assertThat(recorder.offers(c.getId())).isLessThanOrEqualTo(2));
    }

    @Test
    void schemaWithDollarAndDottedKeysIsKept() throws Exception {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("message", Map.of("type", "string"));
        props.put("user.name", Map.of("type", "string"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("$schema", "https://json-schema.org/draft/2020-12/schema");
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("message", "user.name"));
        schema.put("additionalProperties", false);
        schema.put("$defs", Map.of("x.y", Map.of("type", "string")));
        AiReplyFormat json = new AiReplyFormat(AiReplyFormat.Type.JSON, schema, "message");
        scripted.reply(text("{\"message\":\"Hi\",\"user.name\":\"Asha\"}"));

        String id = gateway.submit(new TurnRequest(brand.getId(), "run1", "bot1", "Ask", profileId, null, null,
                List.of("Hi"), List.of(), List.of(), false, json, null, null));
        tick();

        TurnResult r = gateway.result(brand.getId(), id).orElseThrow();
        assertThat(r.status()).as(String.valueOf(r.error())).isEqualTo(TurnStatus.DONE);
        assertThat(r.json()).containsEntry("user.name", "Asha");
        assertThat(scripted.requests().get(0).format().schema()).isEqualTo(schema);

        scripted.reply(new ProviderReply("", List.of(new ToolCall("c1", "finish",
                "{\"outcome\":\"done\",\"fields\":{\"contact.city\":\"Pune\"},\"reply\":null}")), Map.of(),
                new AiUsage(1, 1, 0, 0), null, false));
        String second = gateway.submit(new TurnRequest(brand.getId(), "run1", "bot1", "Ask", profileId, null, null,
                List.of("Bye"), List.of(new AiOutcome("done", "Done", null)),
                List.of(new AiField("contact.city", null, AiFieldType.TEXT)), false, null, r.conversationId(), null));
        tick();

        TurnResult f = gateway.result(brand.getId(), second).orElseThrow();
        assertThat(f.status()).as(String.valueOf(f.error())).isEqualTo(TurnStatus.DONE);
        assertThat(f.finish().fields()).containsEntry("contact.city", "Pune");
    }
}
