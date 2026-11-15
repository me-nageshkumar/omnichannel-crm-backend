package me.nageshk.omnichannel.app.chatbot;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import me.nageshk.omnichannel.ai.api.AiUsage;
import me.nageshk.omnichannel.ai.call.AiCall;
import me.nageshk.omnichannel.ai.call.AiTurnWorker;
import me.nageshk.omnichannel.ai.provider.AiItem;
import me.nageshk.omnichannel.ai.provider.ProviderReply;
import me.nageshk.omnichannel.ai.provider.ProviderRequest;
import me.nageshk.omnichannel.ai.provider.ToolCall;
import me.nageshk.omnichannel.app.ai.AiTestBase;
import me.nageshk.omnichannel.app.automation.AutomationDevice;
import me.nageshk.omnichannel.db.automation.EventData;
import me.nageshk.omnichannel.db.chatbot.ChatbotRun;
import me.nageshk.omnichannel.db.chatbot.ChatbotRunRepository;
import me.nageshk.omnichannel.db.chatbot.ChatbotRunStatus;
import me.nageshk.omnichannel.db.chatbot.ChatbotStep;
import me.nageshk.omnichannel.db.chatbot.IfRunning;
import me.nageshk.omnichannel.db.chatbot.StartedBy;
import me.nageshk.omnichannel.db.chatbot.TaskKind;
import me.nageshk.omnichannel.db.contact.Contact;
import me.nageshk.omnichannel.db.contact.ContactRepository;
import me.nageshk.omnichannel.db.inbox.MessageDirection;
import me.nageshk.omnichannel.db.inbox.MessageSource;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import me.nageshk.omnichannel.db.inbox.WaWebMessage;
import me.nageshk.omnichannel.service.chatbot.node.TaskAnswer;
import me.nageshk.omnichannel.service.chatbot.run.ChatbotRunService;
import me.nageshk.omnichannel.service.chatbot.run.ChatbotRunner;
import me.nageshk.omnichannel.service.rpc.Envelope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Spec §5.5, §5.9: the AI node in conversation mode talks with the customer over many messages until an outcome, an
 * exit word, an exit condition or the turn limit. The inbox is the queue for messages that land while the AI thinks
 * (ruling R1).
 */
@SuppressWarnings("unchecked")
class ChatbotAiConversationIT extends AiTestBase {

    static final String VEENU = "919799750743@c.us";

    @Autowired ChatbotRunService runs;
    @Autowired ChatbotRunRepository runRepo;
    @Autowired ContactRepository contacts;
    @Autowired AiTurnWorker worker;
    @Autowired ChatbotRunner runner;

    private String profileId;

    @BeforeEach
    void setUpProfile() {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("name", "Scripted");
        c.put("provider", "scripted");
        c.put("apiKey", "scripted-key-0123456789");
        String connectionId = ai(HttpMethod.POST, "/connections", manager, c).getBody().get("id").asString();
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("name", "Sales");
        p.put("connectionId", connectionId);
        p.put("model", "scripted-1");
        p.put("instructions", "Be kind.");
        p.put("mcpServers", List.of());
        ResponseEntity<JsonNode> saved = ai(HttpMethod.POST, "/profiles", manager, p);
        assertThat(saved.getStatusCode().is2xxSuccessful()).as(String.valueOf(saved.getBody())).isTrue();
        profileId = saved.getBody().get("id").asString();
    }

    // ---- fixture ----

    private Map<String, Object> conv(Map<String, Object> extra) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("profileId", profileId);
        s.put("mode", "CONVERSATION");
        s.putAll(extra);
        return s;
    }

    private static final Map<String, Object> BOOKED = Map.of(
            "outcomes", List.of(Map.of("id", "booked", "label", "Booked", "description", "the order is placed")),
            "collect", List.of(Map.of("key", "size", "description", "shirt size", "type", "TEXT")));

    private static AiUsage usage() {
        return new AiUsage(3, 2, 0, 0);
    }

    private static ProviderReply text(String t) {
        return new ProviderReply(t, List.of(), Map.of(), usage(), null, false);
    }

    private static ProviderReply finish(String text, String argumentsJson) {
        return new ProviderReply(text, List.of(new ToolCall("c1", "finish", argumentsJson)), Map.of(), usage(), null,
                false);
    }

    private String publish(Map<String, Object> flow) {
        JsonNode bot = createChatbot("Bot", flow);
        ResponseEntity<JsonNode> res = bots(HttpMethod.POST, "/" + bot.get("id").asString() + "/publish", manager, null);
        assertThat(res.getStatusCode().is2xxSuccessful()).as(String.valueOf(res.getBody())).isTrue();
        return bot.get("id").asString();
    }

    private ChatbotRun start(String chatbotId, WaWebChat chat) throws Exception {
        Contact c = contacts.findById(chat.getContactId()).orElseThrow();
        ChatbotRunService.StartResult r = runs.start(brand.getId(), chatbotId, c, chat, StartedBy.test(manager.getId()),
                null, IfRunning.SKIP, true);
        assertThat(r.started()).as(String.valueOf(r.reason())).isTrue();
        r.settled().get(5, TimeUnit.SECONDS);
        return run(r.run().getId());
    }

    private ChatbotRun run(String id) {
        return runRepo.findById(id).orElseThrow();
    }

    private void aiTick() throws Exception {
        worker.tick().get(10, TimeUnit.SECONDS);
    }

    private static Map<String, Object> state(ChatbotRun run, String node) {
        return (Map<String, Object>) run.getVariables().get(node);
    }

    private static List<String> sent(AutomationDevice d) {
        return d.requests("sendMessage").stream().map(e -> e.params().get("content").asString()).toList();
    }

    /** The customer writes on WhatsApp: stored, and offered to a run waiting on the chat. */
    private void say(String text) {
        incoming(n1, VEENU, "chat", text, "Veenu");
    }

    private boolean waitingOn(String runId, TaskKind kind) {
        ChatbotRun r = run(runId);
        return r.getStatus() == ChatbotRunStatus.WAITING && r.getWaiting() != null && r.getWaiting().kind() == kind;
    }

    private boolean waitingOnCustomerAfter(String runId, String node, int turns) {
        ChatbotRun r = run(runId);
        return waitingOn(runId, TaskKind.WHATSAPP_REPLY) && state(r, node) != null
                && Integer.valueOf(turns).equals(state(r, node).get("turns"));
    }

    /**
     * A customer message stored straight into the inbox, as one that lands while the run is not waiting on it. No id
     * is set: Mongo makes one and auditing stamps {@code creationTime} (only on a new document).
     */
    private WaWebMessage stored(WaWebChat chat, String type, String body) {
        WaWebMessage m = new WaWebMessage();
        m.setChatId(chat.getId());
        m.setWorkspaceId(chat.getWorkspaceId());
        m.setCustomerId(chat.getCustomerId());
        m.setPhoneNumberId(chat.getPhoneNumberId());
        m.setWaMessageId("false_" + VEENU + "_" + COUNTER.incrementAndGet());
        m.setDirection(MessageDirection.IN);
        m.setSource(MessageSource.CONTACT);
        m.setType(type);
        m.setBody(body);
        m.setTimestamp(clock.instant());
        return mongo.insert(m);
    }

    private List<AiCall> aiCalls() {
        return mongo.find(new Query().with(Sort.by("createdAt", "_id")), AiCall.class);
    }

    private static List<AiItem> userTexts(ProviderRequest r) {
        return r.input().stream().filter(i -> i instanceof AiItem.UserText).toList();
    }

    // ---- tests ----

    @Test
    void waitsForFirstMessageThenTalks() throws Exception {
        scripted.reply(text("Hello! What are you looking for?"));
        WaWebChat chat = incoming(n1, VEENU, "chat", "hello", "Veenu");
        String id = publish(flow(List.of(node("ai", "ai", "Sales", conv(Map.of("exitWords", List.of("stop"))))),
                List.of()));
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            ChatbotRun run = start(id, chat);
            assertThat(run.getStatus()).isEqualTo(ChatbotRunStatus.WAITING);
            assertThat(run.getWaiting().kind()).isEqualTo(TaskKind.WHATSAPP_REPLY);
            assertThat(run.getWaiting().key()).isEqualTo(chat.getId());
            assertThat(state(run, "Sales")).containsEntry("turns", 0);
            assertThat(aiCalls()).isEmpty();

            say("hi");
            await(() -> waitingOn(run.getId(), TaskKind.AI_RESPONSE));
            assertThat(d.requests("sendMessage")).isEmpty();
            aiTick();
            await(() -> waitingOnCustomerAfter(run.getId(), "Sales", 1));
            assertThat(sent(d)).containsExactly("Hello! What are you looking for?");

            ChatbotRun talking = run(run.getId());
            // the message that started the chat is not the conversation's: only "hi" reached the model
            assertThat(userTexts(scripted.requests().get(0))).containsExactly(new AiItem.UserText("hi"));
            assertThat(scripted.requests().get(0).toolChoice()).isNull();
            assertThat(state(talking, "Sales")).containsEntry("lastReply", "Hello! What are you looking for?")
                    .containsEntry("supersedes", 0);
            // one step for the whole conversation
            assertThat(talking.getSteps()).hasSize(1);
            ChatbotStep step = talking.getSteps().get(0);
            assertThat(step.outcome()).isEqualTo(ChatbotStep.Outcome.WAITING);
            assertThat(step.summary()).contains("Turn 1").contains(aiCalls().get(0).getId());
        }
    }

    @Test
    void initialMessageAndUserMessageStartAtOnce() throws Exception {
        scripted.reply(text("Which product interests you?"));
        WaWebChat chat = incoming(n1, VEENU, "chat", "hello", "Veenu");
        String id = publish(flow(List.of(node("ai", "ai", "Sales", conv(Map.of(
                "initialMessage", "Hi {{contact.name}}, I am the shop assistant.",
                "userMessage", "Greet {{contact.name}} and ask what they need")))), List.of()));
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            ChatbotRun run = start(id, chat);
            assertThat(run.getWaiting().kind()).isEqualTo(TaskKind.AI_RESPONSE);
            assertThat(sent(d)).containsExactly("Hi Veenu, I am the shop assistant.");
            aiTick();
            await(() -> waitingOnCustomerAfter(run.getId(), "Sales", 1));
            assertThat(sent(d)).containsExactly("Hi Veenu, I am the shop assistant.", "Which product interests you?");
            assertThat(userTexts(scripted.requests().get(0)))
                    .containsExactly(new AiItem.UserText("Greet Veenu and ask what they need"));
        }
    }

    @Test
    void finishOutcomeRoutes() throws Exception {
        scripted.reply(text("Which size?"));
        scripted.reply(finish("", "{\"outcome\":\"booked\",\"fields\":{\"size\":\"L\"},\"reply\":\"Booked, size L.\"}"));
        WaWebChat chat = incoming(n1, VEENU, "chat", "hello", "Veenu");
        String id = publish(flow(List.of(
                node("ai", "ai", "Sales", conv(BOOKED)),
                node("m", "send_message", "Confirm", textMessage("Order for size {{Sales.fields.size}}"))),
                List.of(edge("ai", "outcome:booked", "m"))));
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            ChatbotRun run = start(id, chat);
            say("I want a shirt");
            await(() -> waitingOn(run.getId(), TaskKind.AI_RESPONSE));
            aiTick();
            await(() -> waitingOnCustomerAfter(run.getId(), "Sales", 1));
            say("L please");
            await(() -> waitingOn(run.getId(), TaskKind.AI_RESPONSE));
            aiTick();
            await(() -> run(run.getId()).getStatus() == ChatbotRunStatus.COMPLETED);
            assertThat(sent(d)).containsExactly("Which size?", "Booked, size L.", "Order for size L");
            ChatbotRun done = run(run.getId());
            ChatbotStep step = done.getSteps().get(0);
            assertThat(step.output()).isEqualTo("outcome:booked");
            assertThat(step.outcome()).isEqualTo(ChatbotStep.Outcome.DONE);
            assertThat(state(done, "Sales")).containsEntry("outcome", "booked").containsEntry("turns", 2)
                    .containsEntry("fields", Map.of("size", "L")).containsEntry("reply", "Booked, size L.");
            // a conversation lets the model decide when to finish
            assertThat(scripted.requests().get(1).toolChoice()).isNull();
        }
    }

    @Test
    void finishWithTextAndEmptyReply() throws Exception {
        WaWebChat chat = incoming(n1, VEENU, "chat", "hello", "Veenu");
        String id = publish(flow(List.of(
                node("ai", "ai", "Sales", conv(BOOKED)),
                node("m", "send_message", "After", textMessage("after"))),
                List.of(edge("ai", "outcome:booked", "m"))));
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            // the finish carries no reply: the turn's own text goes out
            scripted.reply(finish("Done, thanks!", "{\"outcome\":\"booked\",\"fields\":{\"size\":\"M\"},\"reply\":\"\"}"));
            ChatbotRun first = start(id, chat);
            say("M");
            await(() -> waitingOn(first.getId(), TaskKind.AI_RESPONSE));
            aiTick();
            await(() -> run(first.getId()).getStatus() == ChatbotRunStatus.COMPLETED);
            assertThat(sent(d)).containsExactly("Done, thanks!", "after");
            assertThat(run(first.getId()).getSteps().get(0).output()).isEqualTo("outcome:booked");

            // neither a reply nor a text: nothing is sent, the outcome is taken all the same
            scripted.reply(finish("", "{\"outcome\":\"booked\",\"fields\":{\"size\":\"S\"},\"reply\":null}"));
            ChatbotRun second = start(id, chat);
            say("S");
            await(() -> waitingOn(second.getId(), TaskKind.AI_RESPONSE));
            aiTick();
            await(() -> run(second.getId()).getStatus() == ChatbotRunStatus.COMPLETED);
            assertThat(sent(d)).containsExactly("Done, thanks!", "after", "after");
            assertThat(run(second.getId()).getSteps().get(0).output()).isEqualTo("outcome:booked");
            assertThat(state(run(second.getId()), "Sales")).containsEntry("fields", Map.of("size", "S"));
        }
    }

    @Test
    void exitWordEndsWithoutCallingAi() throws Exception {
        WaWebChat chat = incoming(n1, VEENU, "chat", "hello", "Veenu");
        String id = publish(flow(List.of(
                node("ai", "ai", "Sales", conv(Map.of("exitWords", List.of("stop", "talk to a human")))),
                node("m", "send_message", "Bye", textMessage("Okay, stopping here."))),
                List.of(edge("ai", "exitWord", "m"))));
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            ChatbotRun run = start(id, chat);
            say("STOP ");
            await(() -> run(run.getId()).getStatus() == ChatbotRunStatus.COMPLETED);
            assertThat(sent(d)).containsExactly("Okay, stopping here.");
            ChatbotRun done = run(run.getId());
            assertThat(done.getSteps().get(0).output()).isEqualTo("exitWord");
            assertThat(state(done, "Sales")).containsEntry("exitWord", "stop").containsEntry("turns", 0);
            assertThat(scripted.requests()).isEmpty();
            assertThat(aiCalls()).isEmpty();
        }
    }

    @Test
    void exitConditionOnJson() throws Exception {
        Map<String, Object> schema = Map.of("type", "object", "properties", Map.of(
                "message", Map.of("type", "string"), "done", Map.of("type", "boolean")),
                "required", List.of("message", "done"), "additionalProperties", false);
        scripted.reply(text("{\"message\":\"What is your **name**?\",\"done\":false}"));
        scripted.reply(text("{\"message\":\"Thanks Veenu\",\"done\":true}"));
        WaWebChat chat = incoming(n1, VEENU, "chat", "hello", "Veenu");
        String id = publish(flow(List.of(
                node("ai", "ai", "Sales", conv(Map.of(
                        "replyFormat", Map.of("type", "JSON", "schema", schema, "messageField", "message"),
                        "exitCondition", Map.of("field", "{{Sales.json.done}}", "operator", "is", "value", true)))),
                node("m", "send_message", "Closed", textMessage("Closed after {{Sales.turns}} turns"))),
                List.of(edge("ai", "exitCondition", "m"))));
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            ChatbotRun run = start(id, chat);
            say("hi");
            await(() -> waitingOn(run.getId(), TaskKind.AI_RESPONSE));
            aiTick();
            await(() -> waitingOnCustomerAfter(run.getId(), "Sales", 1));
            say("Veenu");
            await(() -> waitingOn(run.getId(), TaskKind.AI_RESPONSE));
            aiTick();
            await(() -> run(run.getId()).getStatus() == ChatbotRunStatus.COMPLETED);
            // the customer gets the message field, formatted for WhatsApp
            assertThat(sent(d)).containsExactly("What is your *name*?", "Thanks Veenu", "Closed after 2 turns");
            ChatbotRun done = run(run.getId());
            assertThat(done.getSteps().get(0).output()).isEqualTo("exitCondition");
            assertThat((Map<String, Object>) state(done, "Sales").get("json")).containsEntry("done", true);
        }
    }

    @Test
    void maxTurnsReached() throws Exception {
        scripted.reply(text("One"));
        scripted.reply(text("Two"));
        WaWebChat chat = incoming(n1, VEENU, "chat", "hello", "Veenu");
        String id = publish(flow(List.of(
                node("ai", "ai", "Sales", conv(Map.of("maxTurns", 2, "exitWords", List.of("stop")))),
                node("m", "send_message", "Limit", textMessage("Let me get a colleague"))),
                List.of(edge("ai", "maxTurns", "m"))));
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            ChatbotRun run = start(id, chat);
            say("a");
            await(() -> waitingOn(run.getId(), TaskKind.AI_RESPONSE));
            aiTick();
            await(() -> waitingOnCustomerAfter(run.getId(), "Sales", 1));
            say("b");
            await(() -> waitingOn(run.getId(), TaskKind.AI_RESPONSE));
            aiTick();
            await(() -> run(run.getId()).getStatus() == ChatbotRunStatus.COMPLETED);
            assertThat(sent(d)).containsExactly("One", "Two", "Let me get a colleague");
            ChatbotRun done = run(run.getId());
            assertThat(done.getSteps().get(0).output()).isEqualTo("maxTurns");
            assertThat(state(done, "Sales")).containsEntry("turns", 2).containsEntry("reply", "Two");
        }
    }

    @Test
    void messageDuringThinkingSupersedes() throws Exception {
        scripted.reply(text("Which colour?"));
        scripted.reply(text("Blue it is."));
        WaWebChat chat = incoming(n1, VEENU, "chat", "hello", "Veenu");
        String id = publish(flow(List.of(node("ai", "ai", "Sales", conv(Map.of("exitWords", List.of("stop"))))),
                List.of()));
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            ChatbotRun run = start(id, chat);
            say("I want a shirt");
            await(() -> waitingOn(run.getId(), TaskKind.AI_RESPONSE));
            CountDownLatch latch = new CountDownLatch(1);
            scripted.holdUntil(latch);
            CompletableFuture<?> tick = worker.tick();
            await(() -> scripted.requests().size() == 1);
            // the customer goes on while the model thinks
            say("a blue one");
            scripted.holdUntil(null);
            latch.countDown();
            tick.get(10, TimeUnit.SECONDS);
            await(() -> aiCalls().size() == 2 && waitingOn(run.getId(), TaskKind.AI_RESPONSE));
            // the first reply never answered "a blue one": it is not sent
            assertThat(d.requests("sendMessage")).isEmpty();
            List<AiCall> calls = aiCalls();
            assertThat(calls.get(1).turnRequest().supersedesCallId()).isEqualTo(calls.get(0).getId());
            assertThat(calls.get(1).turnRequest().inputs()).containsExactly("I want a shirt", "a blue one");
            assertThat(state(run(run.getId()), "Sales")).containsEntry("supersedes", 1);

            aiTick();
            await(() -> waitingOnCustomerAfter(run.getId(), "Sales", 1));
            assertThat(sent(d)).containsExactly("Blue it is.");
            // the superseded exchange left the transcript: the model sees one customer message with both texts
            ProviderRequest second = scripted.requests().get(1);
            assertThat(second.input()).containsExactly(new AiItem.UserText("I want a shirt\na blue one"));
            assertThat(state(run(run.getId()), "Sales")).containsEntry("supersedes", 0);
        }
    }

    @Test
    void noSupersedeAfterToolUse() throws Exception {
        scripted.reply(new ProviderReply("Checked: blue is in stock.", List.of(), Map.of(), usage(), null, true));
        scripted.reply(text("Anything else?"));
        WaWebChat chat = incoming(n1, VEENU, "chat", "hello", "Veenu");
        String id = publish(flow(List.of(node("ai", "ai", "Sales", conv(Map.of("exitWords", List.of("stop"))))),
                List.of()));
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            ChatbotRun run = start(id, chat);
            say("Is blue in stock?");
            await(() -> waitingOn(run.getId(), TaskKind.AI_RESPONSE));
            CountDownLatch latch = new CountDownLatch(1);
            scripted.holdUntil(latch);
            CompletableFuture<?> tick = worker.tick();
            await(() -> scripted.requests().size() == 1);
            say("and size L");
            scripted.holdUntil(null);
            latch.countDown();
            tick.get(10, TimeUnit.SECONDS);
            // a turn that used a tool is never thrown away: sent, and the waiting message is the next turn
            await(() -> aiCalls().size() == 2 && waitingOn(run.getId(), TaskKind.AI_RESPONSE));
            assertThat(sent(d)).containsExactly("Checked: blue is in stock.");
            List<AiCall> calls = aiCalls();
            assertThat(calls.get(1).turnRequest().supersedesCallId()).isNull();
            assertThat(calls.get(1).turnRequest().inputs()).containsExactly("and size L");
            aiTick();
            await(() -> waitingOnCustomerAfter(run.getId(), "Sales", 2));
            assertThat(sent(d)).containsExactly("Checked: blue is in stock.", "Anything else?");
            List<AiItem> users = userTexts(scripted.requests().get(1));
            assertThat(users).containsExactly(new AiItem.UserText("Is blue in stock?"), new AiItem.UserText("and size L"));
        }
    }

    @Test
    void messageDuringRunningIsNotLost() throws Exception {
        scripted.reply(text("Sure, which size?"));
        scripted.reply(text("Noted: size M."));
        WaWebChat chat = incoming(n1, VEENU, "chat", "hello", "Veenu");
        String id = publish(flow(List.of(node("ai", "ai", "Sales", conv(Map.of("exitWords", List.of("stop"))))),
                List.of()));
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            ChatbotRun run = start(id, chat);
            say("a shirt please");
            await(() -> waitingOn(run.getId(), TaskKind.AI_RESPONSE));
            // while the reply is going out (the run is RUNNING), the customer writes again
            AtomicBoolean once = new AtomicBoolean();
            var normal = d.sendAnswer;
            d.sendAnswer = req -> {
                if (once.compareAndSet(false, true)) stored(chat, "text", "size M");
                return normal.apply(req);
            };
            aiTick();
            await(() -> aiCalls().size() == 2 && waitingOn(run.getId(), TaskKind.AI_RESPONSE));
            assertThat(sent(d)).containsExactly("Sure, which size?");
            assertThat(aiCalls().get(1).turnRequest().inputs()).containsExactly("size M");
            aiTick();
            await(() -> waitingOnCustomerAfter(run.getId(), "Sales", 2));
            assertThat(sent(d)).containsExactly("Sure, which size?", "Noted: size M.");
        }
    }

    @Test
    void historyImportedOldMessageIgnored() throws Exception {
        scripted.reply(text("Hello!"));
        WaWebChat chat = incoming(n1, VEENU, "chat", "hello", "Veenu");
        String id = publish(flow(List.of(node("ai", "ai", "Sales", conv(Map.of("exitWords", List.of("stop"))))),
                List.of()));
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            ChatbotRun run = start(id, chat);
            // an old message stored now (as a history import does): new creationTime, timestamp before the start
            ObjectNode old = payload(VEENU, false, "chat", "an old question from last year", "Veenu");
            old.put("timestamp", clock.instant().minus(Duration.ofDays(200)).getEpochSecond());
            publish(n1, old);
            await(() -> waitingOn(run.getId(), TaskKind.WHATSAPP_REPLY));
            assertThat(aiCalls()).isEmpty();
            say("hi");
            await(() -> waitingOn(run.getId(), TaskKind.AI_RESPONSE));
            aiTick();
            await(() -> waitingOnCustomerAfter(run.getId(), "Sales", 1));
            assertThat(aiCalls()).hasSize(1);
            assertThat(userTexts(scripted.requests().get(0))).containsExactly(new AiItem.UserText("hi"));
        }
    }

    @Test
    void nonTextAsStandIn() throws Exception {
        scripted.reply(text("Nice shirt! Please type the size you want."));
        WaWebChat chat = incoming(n1, VEENU, "chat", "hello", "Veenu");
        String id = publish(flow(List.of(node("ai", "ai", "Sales", conv(Map.of("exitWords", List.of("stop"))))),
                List.of()));
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            ChatbotRun run = start(id, chat);
            WaWebMessage image = stored(chat, "image", "red shirt");
            image.setMedia(new WaWebMessage.Media("image/jpeg", null, 1000L, null, null));
            mongo.save(image);
            assertThat(runs.answer(TaskAnswer.whatsApp(chat.getId(), image))).isTrue();
            await(() -> waitingOn(run.getId(), TaskKind.AI_RESPONSE));
            assertThat(aiCalls().get(0).turnRequest().inputs()).containsExactly("[customer sent an image: red shirt]");
            aiTick();
            await(() -> waitingOnCustomerAfter(run.getId(), "Sales", 1));
            assertThat(sent(d)).containsExactly("Nice shirt! Please type the size you want.");
        }
    }

    @Test
    void agentReplyHandsOver() throws Exception {
        scripted.reply(text("A reply nobody should get"));
        WaWebChat chat = incoming(n1, VEENU, "chat", "hello", "Veenu");
        String id = publish(flow(List.of(node("ai", "ai", "Sales", conv(Map.of("exitWords", List.of("stop"))))),
                List.of()));
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            ChatbotRun run = start(id, chat);
            say("hi");
            await(() -> waitingOn(run.getId(), TaskKind.AI_RESPONSE));
            ResponseEntity<JsonNode> agentSent = call(HttpMethod.POST,
                    "/api/workspaces/" + brand.getId() + "/inbox/chats/" + chat.getId() + "/messages", agent,
                    Map.of("text", "Hi, Rahul here"));
            assertThat(agentSent.getStatusCode()).isEqualTo(HttpStatus.OK);
            await(() -> run(run.getId()).getStatus() == ChatbotRunStatus.HANDED_OVER);
            // the AI answers later: the run is the agent's now, the result is taken as delivered and nothing is sent
            aiTick();
            await(() -> aiCalls().get(0).getDeliveredAt() != null);
            Thread.sleep(200);
            assertThat(sent(d)).containsExactly("Hi, Rahul here");
            ChatbotRun handed = run(run.getId());
            assertThat(handed.getStatus()).isEqualTo(ChatbotRunStatus.HANDED_OVER);
            assertThat(state(handed, "Sales")).containsEntry("turns", 0);
        }
    }

    @Test
    void continueFromReusesConversation() throws Exception {
        scripted.reply(finish("", "{\"outcome\":\"qualified\",\"fields\":{},\"reply\":\"Great, let me show you our plans.\"}"));
        scripted.reply(text("We have Basic and Pro."));
        WaWebChat chat = incoming(n1, VEENU, "chat", "hello", "Veenu");
        Map<String, Object> qualify = conv(Map.of("outcomes",
                List.of(Map.of("id", "qualified", "label", "Qualified", "description", "a real buyer"))));
        String id = publish(flow(List.of(
                node("q", "ai", "Qualify", qualify),
                node("s", "ai", "Sales", conv(Map.of("continueFrom", "Qualify",
                        "userMessage", "Now offer the plans", "exitWords", List.of("stop"))))),
                List.of(edge("q", "outcome:qualified", "s"))));
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            ChatbotRun run = start(id, chat);
            say("I run a shop");
            await(() -> waitingOn(run.getId(), TaskKind.AI_RESPONSE));
            aiTick();
            await(() -> aiCalls().size() == 2 && waitingOn(run.getId(), TaskKind.AI_RESPONSE));
            List<AiCall> calls = aiCalls();
            assertThat(calls.get(1).getConversationId()).isEqualTo(calls.get(0).getConversationId());
            aiTick();
            await(() -> waitingOnCustomerAfter(run.getId(), "Sales", 1));
            assertThat(sent(d)).containsExactly("Great, let me show you our plans.", "We have Basic and Pro.");
            ChatbotRun talking = run(run.getId());
            assertThat(state(talking, "Sales").get("conversationId"))
                    .isEqualTo(state(talking, "Qualify").get("conversationId"));
            // the earlier node's open finish call is answered before the new input
            ProviderRequest second = scripted.requests().get(1);
            assertThat(second.input()).anyMatch(i -> i instanceof AiItem.ToolResultItem t && t.callId().equals("c1"));
            assertThat(second.input().get(second.input().size() - 1))
                    .isEqualTo(new AiItem.UserText("Now offer the plans"));
        }
    }

    @Test
    void longReplyGoesOutInPartsAndAFailedPartIsResentAlone() throws Exception {
        String first = "a".repeat(2999) + ".";
        String second = "b".repeat(1499) + ".";
        scripted.reply(text(first + "\n\n" + second));
        WaWebChat chat = incoming(n1, VEENU, "chat", "hello", "Veenu");
        String id = publish(flow(List.of(node("ai", "ai", "Sales", conv(Map.of("exitWords", List.of("stop"))))),
                List.of()));
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            ChatbotRun run = start(id, chat);
            say("tell me everything");
            await(() -> waitingOn(run.getId(), TaskKind.AI_RESPONSE));
            var normal = d.sendAnswer;
            // the first part goes out, the second finds WhatsApp Web not ready
            d.sendAnswer = req -> d.requests("sendMessage").size() == 2
                    ? Envelope.errorResponse(req.id(), "WHATSAPP_NOT_READY", "not ready") : normal.apply(req);
            aiTick();
            await(() -> run(run.getId()).getRetryAt() != null);
            Map<String, Object> pending = (Map<String, Object>) state(run(run.getId()), "Sales").get("pendingSend");
            assertThat(pending).containsEntry("sent", 1).containsEntry("then", "CONTINUE");
            d.sendAnswer = normal;
            clock.advance(Duration.ofSeconds(31));
            runner.retryDue().get(5, TimeUnit.SECONDS);
            await(() -> waitingOnCustomerAfter(run.getId(), "Sales", 1));
            // part one once, part two twice (the failed try and the resend), and no new AI call
            assertThat(sent(d)).containsExactly(first, second, second);
            assertThat(state(run(run.getId()), "Sales")).doesNotContainKey("pendingSend");
            aiTick();
            assertThat(aiCalls()).hasSize(1);
            assertThat(scripted.requests()).hasSize(1);
        }
    }

    @Test
    void finishDuringThinkingLeavesNewMessagesToTheInbox() throws Exception {
        scripted.reply(finish("", "{\"outcome\":\"booked\",\"fields\":{\"size\":\"L\"},\"reply\":\"Booked, size L.\"}"));
        WaWebChat chat = incoming(n1, VEENU, "chat", "hello", "Veenu");
        String id = publish(flow(List.of(node("ai", "ai", "Sales", conv(BOOKED))), List.of()));
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            ChatbotRun run = start(id, chat);
            say("size L please");
            await(() -> waitingOn(run.getId(), TaskKind.AI_RESPONSE));
            CountDownLatch latch = new CountDownLatch(1);
            scripted.holdUntil(latch);
            CompletableFuture<?> tick = worker.tick();
            await(() -> scripted.requests().size() == 1);
            say("and gift wrap");
            scripted.holdUntil(null);
            latch.countDown();
            tick.get(10, TimeUnit.SECONDS);
            // a finish is never thrown away: the outcome is taken, the new message is recorded and left to the inbox
            await(() -> run(run.getId()).getStatus() == ChatbotRunStatus.COMPLETED);
            assertThat(sent(d)).containsExactly("Booked, size L.");
            assertThat(aiCalls()).hasSize(1);
            ChatbotRun done = run(run.getId());
            assertThat(done.getSteps().get(0).output()).isEqualTo("outcome:booked");
            List<String> pendingIds = (List<String>) state(done, "Sales").get("pendingAfterFinish");
            assertThat(pendingIds).hasSize(1);
            assertThat(mongo.findById(pendingIds.get(0), WaWebMessage.class).getBody()).isEqualTo("and gift wrap");
        }
    }

    /** The device stores {@code text} as a customer message while it answers the bot's first send. */
    private void customerWritesDuringFirstSend(AutomationDevice d, WaWebChat chat, String text) {
        AtomicBoolean once = new AtomicBoolean();
        var normal = d.sendAnswer;
        d.sendAnswer = req -> {
            if (once.compareAndSet(false, true)) stored(chat, "text", text);
            return normal.apply(req);
        };
    }

    @Test
    void messageBeforeTheAiNodeIsItsFirstInput() throws Exception {
        scripted.reply(text("Red shoes, lovely. Which size?"));
        WaWebChat chat = incoming(n1, VEENU, "chat", "hi", "Veenu");
        String id = publish(flow(List.of(
                node("m", "send_message", "Welcome", textMessage("Welcome to the shop!")),
                node("ai", "ai", "Sales", conv(Map.of("exitWords", List.of("stop"))))),
                List.of(edge("m", "next", "ai"))));
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            // the customer goes on while the Send message node runs, before the AI node starts
            customerWritesDuringFirstSend(d, chat, "I want red shoes");
            ChatbotRun run = start(id, chat);
            await(() -> waitingOn(run.getId(), TaskKind.AI_RESPONSE));
            // the trigger "hi" stays out; what came after it is the first turn
            assertThat(aiCalls().get(0).turnRequest().inputs()).containsExactly("I want red shoes");
            aiTick();
            await(() -> waitingOnCustomerAfter(run.getId(), "Sales", 1));
            assertThat(sent(d)).containsExactly("Welcome to the shop!", "Red shoes, lovely. Which size?");
        }
    }

    @Test
    void messageAfterTheTriggerButBeforeTheRunStarted() throws Exception {
        scripted.reply(text("Which colour?"));
        WaWebChat chat = incoming(n1, VEENU, "chat", "hi", "Veenu");
        WaWebMessage trigger = mongo.findOne(new Query(Criteria.where("chatId").is(chat.getId())
                .and("body").is("hi")), WaWebMessage.class);
        // a burst: the second message is stored before the rule's run is even created
        incoming(n1, VEENU, "chat", "I want shoes", "Veenu");
        String id = publish(flow(List.of(node("ai", "ai", "Sales", conv(Map.of("exitWords", List.of("stop"))))),
                List.of()));
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            Contact c = contacts.findById(chat.getContactId()).orElseThrow();
            EventData event = new EventData(null, new EventData.MessageData(trigger.getId(), "hi", "TEXT", null), null,
                    null, null, null, null, null);
            ChatbotRunService.StartResult r = runs.start(brand.getId(), id, c, chat, StartedBy.test(manager.getId()),
                    event, IfRunning.SKIP, true);
            r.settled().get(5, TimeUnit.SECONDS);
            String runId = r.run().getId();
            await(() -> waitingOn(runId, TaskKind.AI_RESPONSE));
            assertThat(aiCalls().get(0).turnRequest().inputs()).containsExactly("I want shoes");
        }
    }

    @Test
    void messageAfterAnAskAnswerIsTheFirstInput() throws Exception {
        scripted.reply(text("Thanks Asha, we deliver to Pune."));
        WaWebChat chat = incoming(n1, VEENU, "chat", "hello", "Veenu");
        String id = publish(flow(List.of(
                node("q", "ask_question", "AskName", Map.of("text", "Your name?", "answerType", "TEXT")),
                node("m", "send_message", "Thanks", textMessage("Thanks {{AskName.answer}}")),
                node("ai", "ai", "Sales", conv(Map.of("exitWords", List.of("stop"))))),
                List.of(edge("q", "next", "m"), edge("m", "next", "ai"))));
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            ChatbotRun run = start(id, chat);
            await(() -> d.requests("sendMessage").size() == 1);
            // the Ask answer is that node's; the next message lands while "Thanks" goes out
            AtomicBoolean once = new AtomicBoolean();
            var normal = d.sendAnswer;
            d.sendAnswer = req -> {
                if (req.params().get("content").asString().startsWith("Thanks") && once.compareAndSet(false, true)) {
                    stored(chat, "text", "and my city is Pune");
                }
                return normal.apply(req);
            };
            say("Asha");
            await(() -> waitingOn(run.getId(), TaskKind.AI_RESPONSE));
            assertThat(aiCalls().get(0).turnRequest().inputs()).containsExactly("and my city is Pune");
            assertThat(state(run(run.getId()), "AskName")).containsEntry("answer", "Asha");
        }
    }
}
