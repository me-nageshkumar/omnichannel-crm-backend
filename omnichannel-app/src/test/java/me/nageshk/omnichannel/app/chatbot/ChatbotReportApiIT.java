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

    private JsonNode runs(String id, String query) {
        ResponseEntity<JsonNode> r = bots(HttpMethod.GET, "/" + id + "/report/runs" + query, manager, null);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    private long total(String id, String query) {
        return runs(id, query + (query.isEmpty() ? "?" : "&") + "size=100").get("total").asLong();
    }

    @Test
    void runsListMatchesReportCounts() {
        String id = createChatbot("Bot", sayFlow()).get("id").asString();
        run(id, ChatbotRunStatus.RUNNING, null, false, n1.getId(), step(0, "w", "Welcome", null));
        run(id, ChatbotRunStatus.WAITING, null, false, n1.getId(), step(0, "w", "Welcome", null), step(1, "c", "Counsellor", null));
        run(id, ChatbotRunStatus.COMPLETED, end(Chat.CLOSED, null), false, n1.getId(), step(0, "w", "Welcome", null),
                step(1, "c", "Counsellor", null));
        run(id, ChatbotRunStatus.COMPLETED, null, false, n1.getId(), step(0, "w", "Welcome", null));
        run(id, ChatbotRunStatus.ENDED, end(Chat.ROUTED, null), false, n1.getId(), step(0, "w", "Welcome", null),
                step(1, "c", "Counsellor", null));
        run(id, ChatbotRunStatus.STOPPED, end(Chat.LEFT, null), false, n2.getId(), step(0, "w", "Welcome", null));
        run(id, ChatbotRunStatus.FAILED, null, false, n1.getId(), step(0, "w", "Welcome", null));
        run(id, ChatbotRunStatus.HANDED_OVER, end(Chat.HANDED_OVER, agent.getId()), false, n1.getId(),
                step(0, "w", "Welcome", null), step(1, "c", "Counsellor", null));
        run(id, ChatbotRunStatus.HANDED_OVER, end(Chat.HANDED_OVER, null), false, n2.getId(), step(0, "w", "Welcome", null));
        run(id, ChatbotRunStatus.JUMPED, end(Chat.LEFT, null), false, n1.getId(), step(0, "w", "Welcome", null),
                step(1, "gone", "Old", null));
        run(id, ChatbotRunStatus.COMPLETED, null, true, n1.getId(), step(0, "w", "Welcome", null));
        JsonNode rep = report(id, "", manager);
        JsonNode t = rep.get("totals");
        assertThat(total(id, "")).isEqualTo(t.get("started").asLong());
        assertThat(total(id, "?status=RUNNING") + total(id, "?status=WAITING")).isEqualTo(t.get("running").asLong());
        assertThat(total(id, "?status=COMPLETED")).isEqualTo(t.get("completed").asLong());
        assertThat(total(id, "?status=ENDED")).isEqualTo(t.get("wentSilent").asLong());
        assertThat(total(id, "?status=STOPPED")).isEqualTo(t.get("stopped").asLong());
        assertThat(total(id, "?status=FAILED")).isEqualTo(t.get("failed").asLong());
        assertThat(total(id, "?status=HANDED_OVER")).isEqualTo(t.get("handedOver").asLong());
        assertThat(total(id, "?status=JUMPED")).isEqualTo(t.get("jumped").asLong());
        JsonNode c = t.get("chat");
        assertThat(total(id, "?chat=TO_AGENT")).isEqualTo(c.get("toAgent").asLong()).isEqualTo(1);
        assertThat(total(id, "?chat=TO_QUEUE")).isEqualTo(c.get("toQueue").asLong()).isEqualTo(2);
        assertThat(total(id, "?chat=CLOSED")).isEqualTo(c.get("closed").asLong()).isEqualTo(1);
        assertThat(total(id, "?chat=LEFT")).isEqualTo(c.get("left").asLong()).isEqualTo(2);
        assertThat(total(id, "?chat=NOT_RECORDED")).isEqualTo(c.get("notRecorded").asLong()).isEqualTo(2);
        for (JsonNode s : rep.get("steps")) {
            String node = s.get("nodeId").asString();
            assertThat(total(id, "?nodeId=" + node + "&reached=true")).as("reached " + node).isEqualTo(s.get("reached").asLong());
            long ended = 0;
            for (var e : s.get("endedHere").properties()) {
                assertThat(total(id, "?nodeId=" + node + "&status=" + e.getKey())).as(node + " " + e.getKey())
                        .isEqualTo(e.getValue().asLong());
                ended += e.getValue().asLong();
            }
            assertThat(total(id, "?nodeId=" + node)).as("ended at " + node).isEqualTo(ended);
        }
        assertThat(total(id, "?phoneNumberId=" + n2.getId())).isEqualTo(2);
    }

    @Test
    void givenFiltersByProgressCount() {
        Map<String, Object> ai = Map.of("mode", "CONVERSATION", "collect", List.of(
                Map.of("key", "name", "type", "TEXT"), Map.of("key", "city", "type", "TEXT")));
        String id = createChatbot("Bot", flow(List.of(node("ai", "ai", "Counsellor", ai)), List.of())).get("id").asString();
        ChatbotRunStatus e = ChatbotRunStatus.ENDED;
        run(id, e, null, false, n1.getId(), step(0, "ai", "Counsellor", Map.of()));
        run(id, e, null, false, n1.getId(), step(0, "ai", "Counsellor", Map.of("name", "A", "other", "x")));
        run(id, e, null, false, n1.getId(), step(0, "ai", "Counsellor", Map.of("name", "A", "city", "Pune")));
        run(id, ChatbotRunStatus.WAITING, null, false, n1.getId(), step(0, "ai", "Counsellor", Map.of("name", "A")));
        JsonNode rep = report(id, "", manager).get("progress").get(0);
        for (int k = 0; k < 3; k++) {
            assertThat(total(id, "?nodeId=ai&given=" + k)).as("given " + k).isEqualTo(rep.get("byCount").get(k).asLong()).isEqualTo(1);
        }
        JsonNode one = runs(id, "?nodeId=ai&given=1");
        assertThat(one.get("items")).hasSize(1);
        assertThat(one.get("items").get(0).get("progress").get("name").asString()).isEqualTo("A");
        assertThat(bots(HttpMethod.GET, "/" + id + "/report/runs?given=1", manager, null).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void rowCarriesChatDealAndOwner() {
        String id = createChatbot("Bot", sayFlow()).get("id").asString();
        me.nageshk.omnichannel.crm.deal.CrmDeal deal = new me.nageshk.omnichannel.crm.deal.CrmDeal();
        deal.setWorkspaceId(brand.getId());
        deal.setCustomerId(brand.getCustomerId());
        deal.setPipelineId("pipe-1");
        deal.setOwnerId(agent.getId());
        deal = mongo.save(deal);
        me.nageshk.omnichannel.crm.deal.CrmDeal noOwner = new me.nageshk.omnichannel.crm.deal.CrmDeal();
        noOwner.setWorkspaceId(brand.getId());
        noOwner.setCustomerId(brand.getCustomerId());
        noOwner.setPipelineId("pipe-2");
        noOwner = mongo.save(noOwner);
        me.nageshk.omnichannel.db.contact.Contact contact = new me.nageshk.omnichannel.db.contact.Contact();
        contact.setWorkspaceId(brand.getId());
        contact.setName("Asha");
        contact.setPhone("919800000001");
        contact = mongo.save(contact);
        ChatbotStep crm = new ChatbotStep(1, "crm", "Add", "add_to_crm", clock.instant(), clock.instant(),
                ChatbotStep.Outcome.DONE, "next", null, null, Map.of("dealId", deal.getId()), null, null);
        ChatbotRun r = run(id, ChatbotRunStatus.HANDED_OVER, new RunEnding(Chat.HANDED_OVER, agent.getId(), null, clock.instant()),
                false, n1.getId(), step(0, "w", "Welcome", null), crm);
        r.setContactId(contact.getId());
        r.setChatId("chat-1");
        runRepo.save(r);
        ChatbotStep crm2 = new ChatbotStep(1, "crm", "Add", "add_to_crm", clock.instant(), clock.instant(),
                ChatbotStep.Outcome.DONE, "next", null, null, Map.of("dealId", noOwner.getId()), null, null);
        run(id, ChatbotRunStatus.COMPLETED, null, false, n2.getId(), step(0, "w", "Welcome", null), crm2);
        run(id, ChatbotRunStatus.COMPLETED, null);
        JsonNode items = runs(id, "").get("items");
        JsonNode row = null, ownerless = null, plain = null;
        for (JsonNode i : items) {
            if (i.get("runId").asString().equals(r.getId())) row = i;
            else if (i.get("dealId") != null && !i.get("dealId").isNull()) ownerless = i;
            else plain = i;
        }
        assertThat(row.get("contactName").asString()).isEqualTo("Asha");
        assertThat(row.get("phone").asString()).isEqualTo("919800000001");
        assertThat(row.get("numberLabel").isNull() ? n1.getLabel() : row.get("numberLabel").asString()).isEqualTo(n1.getLabel());
        assertThat(row.get("chatId").asString()).isEqualTo("chat-1");
        assertThat(row.get("status").asString()).isEqualTo("HANDED_OVER");
        assertThat(row.get("ending").get("chat").asString()).isEqualTo("HANDED_OVER");
        assertThat(row.get("agentName").asString()).isEqualTo(agent.getName());
        assertThat(row.get("dealId").asString()).isEqualTo(deal.getId());
        assertThat(row.get("dealPipelineId").asString()).isEqualTo("pipe-1");
        assertThat(row.get("leadOwnerName").asString()).isEqualTo(agent.getName());
        assertThat(ownerless.get("dealPipelineId").asString()).isEqualTo("pipe-2");
        assertThat(ownerless.get("leadOwnerName").isNull()).isTrue();
        assertThat(plain.get("dealId").isNull()).isTrue();
        assertThat(plain.get("leadOwnerName").isNull()).isTrue();
    }

    @Test
    void newestFirstAndPaged() {
        String id = createChatbot("Bot", sayFlow()).get("id").asString();
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            ChatbotRun r = run(id, ChatbotRunStatus.COMPLETED, null);
            r.setCreatedAt(clock.instant().minus(Duration.ofMinutes(100 - i)));
            runRepo.save(r);
            ids.add(0, r.getId());
        }
        JsonNode p0 = runs(id, "?page=0&size=2");
        assertThat(p0.get("total").asLong()).isEqualTo(5);
        assertThat(p0.get("page").asInt()).isZero();
        assertThat(p0.get("size").asInt()).isEqualTo(2);
        assertThat(p0.get("items").get(0).get("runId").asString()).isEqualTo(ids.get(0));
        assertThat(p0.get("items").get(1).get("runId").asString()).isEqualTo(ids.get(1));
        JsonNode p2 = runs(id, "?page=2&size=2");
        assertThat(p2.get("items")).hasSize(1);
        assertThat(p2.get("items").get(0).get("runId").asString()).isEqualTo(ids.get(4));
        assertThat(runs(id, "?size=500").get("size").asInt()).isEqualTo(100);
        assertThat(bots(HttpMethod.GET, "/" + id + "/report/runs", agent, null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
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
