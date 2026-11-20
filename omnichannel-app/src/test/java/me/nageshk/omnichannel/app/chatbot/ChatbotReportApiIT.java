package me.nageshk.omnichannel.app.chatbot;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import me.nageshk.omnichannel.db.chatbot.ChatbotRun;
import me.nageshk.omnichannel.db.chatbot.ChatbotRunRepository;
import me.nageshk.omnichannel.db.chatbot.ChatbotRunStatus;
import me.nageshk.omnichannel.db.chatbot.ChatbotStep;
import me.nageshk.omnichannel.db.chatbot.RunEnding;
import me.nageshk.omnichannel.db.chatbot.RunEnding.Chat;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/** Lead-owner report spec §6.4: the chatbot report, over runs seeded straight into chatbotRuns. */
class ChatbotReportApiIT extends ChatbotTestBase {

    @Autowired ChatbotRunRepository runRepo;

    private final AtomicInteger seq = new AtomicInteger();

    private Map<String, Object> sayFlow() {
        return flow(List.of(
                node("w", "send_message", "Welcome", textMessage("Hi")),
                node("c", "send_message", "Counsellor", textMessage("Counsellor"))), List.of(edge("w", "next", "c")));
    }

    private ChatbotStep step(int i, String nodeId, String name, Map<String, Object> progress) {
        return new ChatbotStep(i, nodeId, name, "send_message", clock.instant(), clock.instant(), ChatbotStep.Outcome.DONE,
                "next", null, null, null, null, progress);
    }

    private ChatbotRun run(String chatbotId, ChatbotRunStatus status, RunEnding ending, boolean test, String phone,
            ChatbotStep... steps) {
        ChatbotRun r = new ChatbotRun();
        r.setCustomerId(brand.getCustomerId());
        r.setWorkspaceId(brand.getId());
        r.setChatbotId(chatbotId);
        r.setChatbotName("Bot");
        r.setVersionNumber(1);
        r.setContactId("contact-" + seq.incrementAndGet());
        r.setPhoneNumberId(phone);
        r.setStatus(status);
        r.setTest(test);
        r.setSteps(new ArrayList<>(List.of(steps)));
        r.setCreatedAt(clock.instant().minus(Duration.ofMinutes(60 - seq.get() % 50)));
        if (!status.active()) r.setFinishedAt(clock.instant());
        r.setEnding(ending);
        return runRepo.save(r);
    }

    private ChatbotRun run(String chatbotId, ChatbotRunStatus status, RunEnding ending) {
        return run(chatbotId, status, ending, false, n1.getId(), step(0, "w", "Welcome", null));
    }

    private RunEnding end(Chat chat, String agent) {
        return new RunEnding(chat, agent, null, clock.instant());
    }

    private JsonNode report(String id, String query, me.nageshk.omnichannel.db.user.User who) {
        ResponseEntity<JsonNode> r = bots(HttpMethod.GET, "/" + id + "/report" + query, who, null);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    @Test
    void totalsByStatusAndChat() {
        String id = createChatbot("Bot", sayFlow()).get("id").asString();
        run(id, ChatbotRunStatus.RUNNING, null);
        run(id, ChatbotRunStatus.WAITING, null);
        run(id, ChatbotRunStatus.COMPLETED, end(Chat.CLOSED, null));
        run(id, ChatbotRunStatus.COMPLETED, null);
        run(id, ChatbotRunStatus.ENDED, end(Chat.ROUTED, null));
        run(id, ChatbotRunStatus.STOPPED, end(Chat.LEFT, null));
        run(id, ChatbotRunStatus.FAILED, null);
        run(id, ChatbotRunStatus.HANDED_OVER, end(Chat.HANDED_OVER, agent.getId()));
        run(id, ChatbotRunStatus.HANDED_OVER, end(Chat.HANDED_OVER, null));
        run(id, ChatbotRunStatus.JUMPED, end(Chat.LEFT, null));
        JsonNode t = report(id, "", manager).get("totals");
        assertThat(t.get("started").asLong()).isEqualTo(10);
        assertThat(t.get("running").asLong()).isEqualTo(2);
        assertThat(t.get("completed").asLong()).isEqualTo(2);
        assertThat(t.get("wentSilent").asLong()).isEqualTo(1);
        assertThat(t.get("stopped").asLong()).isEqualTo(1);
        assertThat(t.get("failed").asLong()).isEqualTo(1);
        assertThat(t.get("handedOver").asLong()).isEqualTo(2);
        assertThat(t.get("jumped").asLong()).isEqualTo(1);
        JsonNode c = t.get("chat");
        assertThat(c.get("toAgent").asLong()).isEqualTo(1);
        assertThat(c.get("toQueue").asLong()).isEqualTo(2);
        assertThat(c.get("closed").asLong()).isEqualTo(1);
        assertThat(c.get("left").asLong()).isEqualTo(2);
        assertThat(c.get("notRecorded").asLong()).isEqualTo(2);
    }

    @Test
    void testRunsAreLeftOut() {
        String id = createChatbot("Bot", sayFlow()).get("id").asString();
        run(id, ChatbotRunStatus.COMPLETED, null);
        run(id, ChatbotRunStatus.COMPLETED, null, true, n1.getId(), step(0, "w", "Welcome", null));
        assertThat(report(id, "", manager).get("totals").get("started").asLong()).isEqualTo(1);
        assertThat(report(id, "", manager).get("steps").get(0).get("reached").asLong()).isEqualTo(1);
    }

    @Test
    void stepsInFlowOrderWithRemovedLast() {
        String id = createChatbot("Bot", sayFlow()).get("id").asString();
        run(id, ChatbotRunStatus.ENDED, null, false, n1.getId(), step(0, "w", "Welcome", null),
                step(1, "gone", "Old name", null));
        run(id, ChatbotRunStatus.COMPLETED, null, false, n1.getId(), step(0, "w", "Welcome", null),
                step(1, "gone", "Older name", null));
        JsonNode steps = report(id, "", manager).get("steps");
        assertThat(steps).hasSize(3);
        assertThat(steps.get(0).get("nodeId").asString()).isEqualTo("w");
        assertThat(steps.get(0).get("name").asString()).isEqualTo("Welcome");
        assertThat(steps.get(0).get("type").asString()).isEqualTo("send_message");
        assertThat(steps.get(0).get("removed").asBoolean()).isFalse();
        assertThat(steps.get(1).get("nodeId").asString()).isEqualTo("c");
        assertThat(steps.get(1).get("reached").asLong()).isZero();
        assertThat(steps.get(2).get("nodeId").asString()).isEqualTo("gone");
        assertThat(steps.get(2).get("removed").asBoolean()).isTrue();
        assertThat(steps.get(2).get("name").asString()).isEqualTo("Older name");
        assertThat(steps.get(2).get("reached").asLong()).isEqualTo(2);
    }

    @Test
    void reachedAndEndedHere() {
        String id = createChatbot("Bot", sayFlow()).get("id").asString();
        run(id, ChatbotRunStatus.ENDED, null, false, n1.getId(), step(0, "w", "Welcome", null),
                step(1, "c", "Counsellor", null));
        run(id, ChatbotRunStatus.COMPLETED, null, false, n1.getId(), step(0, "w", "Welcome", null),
                step(1, "c", "Counsellor", null), step(2, "w", "Welcome", null));
        JsonNode steps = report(id, "", manager).get("steps");
        JsonNode counsellor = steps.get(1);
        assertThat(counsellor.get("reached").asLong()).isEqualTo(2);
        assertThat(counsellor.get("endedHere").get("ENDED").asLong()).isEqualTo(1);
        assertThat(counsellor.get("endedHere").has("COMPLETED")).isFalse();
        assertThat(steps.get(0).get("reached").asLong()).isEqualTo(2);
        assertThat(steps.get(0).get("endedHere").get("COMPLETED").asLong()).isEqualTo(1);
    }

    @Test
    void runningRunsNeverEndHere() {
        String id = createChatbot("Bot", sayFlow()).get("id").asString();
        run(id, ChatbotRunStatus.WAITING, null, false, n1.getId(), step(0, "w", "Welcome", null),
                step(1, "c", "Counsellor", null));
        JsonNode counsellor = report(id, "", manager).get("steps").get(1);
        assertThat(counsellor.get("reached").asLong()).isEqualTo(1);
        assertThat(counsellor.get("endedHere").size()).isZero();
    }

    @Test
    void progressBuckets() {
        Map<String, Object> ai = Map.of("mode", "CONVERSATION", "collect", List.of(
                Map.of("key", "name", "description", "The customer's name", "type", "TEXT"),
                Map.of("key", "city", "description", "A very long description of the city the customer lives in, in full", "type", "TEXT"),
                Map.of("key", "experience", "type", "NUMBER")));
        String id = createChatbot("Bot", flow(List.of(node("ai", "ai", "Counsellor", ai)), List.of())).get("id").asString();
        ChatbotRunStatus e = ChatbotRunStatus.ENDED;
        run(id, e, null, false, n1.getId(), step(0, "ai", "Counsellor", Map.of()));
        run(id, e, null, false, n1.getId(), step(0, "ai", "Counsellor", Map.of("name", "A")));
        run(id, e, null, false, n1.getId(), step(0, "ai", "Counsellor", Map.of("name", "A", "city", "Pune")));
        run(id, ChatbotRunStatus.WAITING, null, false, n1.getId(), step(0, "ai", "Counsellor", Map.of("name", "A")));
        JsonNode rows = report(id, "", manager).get("progress");
        assertThat(rows).hasSize(1);
        JsonNode row = rows.get(0);
        assertThat(row.get("nodeId").asString()).isEqualTo("ai");
        assertThat(row.get("byCount")).hasSize(4);
        assertThat(List.of(row.get("byCount").get(0).asLong(), row.get("byCount").get(1).asLong(),
                row.get("byCount").get(2).asLong(), row.get("byCount").get(3).asLong())).containsExactly(1L, 1L, 1L, 0L);
        assertThat(row.get("fields").get(0).get("key").asString()).isEqualTo("name");
        assertThat(row.get("fields").get(0).get("given").asLong()).isEqualTo(2);
        assertThat(row.get("fields").get(1).get("given").asLong()).isEqualTo(1);
        assertThat(row.get("fields").get(1).get("label").asString()).hasSize(40);
        assertThat(row.get("fields").get(2).get("label").asString()).isEqualTo("experience");
    }

    @Test
    void numberFilter() {
        String id = createChatbot("Bot", sayFlow()).get("id").asString();
        run(id, ChatbotRunStatus.COMPLETED, null, false, n1.getId(), step(0, "w", "Welcome", null));
        run(id, ChatbotRunStatus.COMPLETED, null, false, n2.getId(), step(0, "w", "Welcome", null));
        run(id, ChatbotRunStatus.COMPLETED, null, false, n2.getId(), step(0, "w", "Welcome", null));
        assertThat(report(id, "", manager).get("totals").get("started").asLong()).isEqualTo(3);
        assertThat(report(id, "?phoneNumberId=" + n2.getId(), manager).get("totals").get("started").asLong()).isEqualTo(2);
    }

    @Test
    void rangeFilterAndLimit() {
        String id = createChatbot("Bot", sayFlow()).get("id").asString();
        run(id, ChatbotRunStatus.COMPLETED, null);
        Instant to = clock.instant().plusSeconds(60);
        assertThat(report(id, "?from=" + to.minus(Duration.ofDays(2)) + "&to=" + to, manager).get("totals").get("started").asLong())
                .isEqualTo(1);
        assertThat(report(id, "?from=" + to.plus(Duration.ofDays(1)) + "&to=" + to.plus(Duration.ofDays(2)), manager)
                .get("totals").get("started").asLong()).isZero();
    }

    @Test
    void rangeOver92DaysIs400() {
        String id = createChatbot("Bot", sayFlow()).get("id").asString();
        Instant to = clock.instant();
        ResponseEntity<JsonNode> r = bots(HttpMethod.GET,
                "/" + id + "/report?from=" + to.minus(Duration.ofDays(93)) + "&to=" + to, manager, null);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(r.getBody().get("fields").get("from").asString()).isEqualTo("The range can be at most 92 days");
    }

    @Test
    void needsChatbotView() {
        String id = createChatbot("Bot", sayFlow()).get("id").asString();
        assertThat(bots(HttpMethod.GET, "/" + id + "/report", agent, null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void reportOf10kRunsUnderOneSecond() {
        String id = createChatbot("Bot", sayFlow()).get("id").asString();
        List<ChatbotRun> batch = new ArrayList<>();
        for (int i = 0; i < 10_000; i++) {
            ChatbotRun r = new ChatbotRun();
            r.setCustomerId(brand.getCustomerId());
            r.setWorkspaceId(brand.getId());
            r.setChatbotId(id);
            r.setChatbotName("Bot");
            r.setContactId("bulk-" + i);
            r.setPhoneNumberId(n1.getId());
            r.setStatus(ChatbotRunStatus.COMPLETED);
            List<ChatbotStep> steps = new ArrayList<>();
            for (int s = 0; s < 8; s++) steps.add(step(s, s % 2 == 0 ? "w" : "c", s % 2 == 0 ? "Welcome" : "Counsellor", null));
            r.setSteps(steps);
            r.setCreatedAt(clock.instant().minus(Duration.ofMinutes(i % 1000)));
            r.setFinishedAt(clock.instant());
            batch.add(r);
        }
        runRepo.saveAll(batch);
        report(id, "", manager);
        long t0 = System.nanoTime();
        JsonNode body = report(id, "", manager);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertThat(body.get("totals").get("started").asLong()).isEqualTo(10_000);
        assertThat(ms).as("second call, ms").isLessThan(1000);
    }
}
