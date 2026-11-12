package me.nageshk.omnichannel.app.chatbot;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import me.nageshk.omnichannel.app.automation.AutomationDevice;
import me.nageshk.omnichannel.db.automation.AutomationAction;
import me.nageshk.omnichannel.db.automation.AutomationActionType;
import me.nageshk.omnichannel.db.automation.AutomationEventKind;
import me.nageshk.omnichannel.db.automation.AutomationRun;
import me.nageshk.omnichannel.db.automation.RuleStatus;
import me.nageshk.omnichannel.db.automation.StepOutcome;
import me.nageshk.omnichannel.db.chatbot.ChatbotRun;
import me.nageshk.omnichannel.db.chatbot.ChatbotRunRepository;
import me.nageshk.omnichannel.db.chatbot.ChatbotRunStatus;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import me.nageshk.omnichannel.service.automation.AutomationWorker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/** Spec §7, §3.3: a test run starts at once on a named contact; drafts never run on live events. */
class ChatbotTestRunIT extends ChatbotTestBase {

    static final String VEENU = "919799750743@c.us";

    @Autowired ChatbotRunRepository runRepo;
    @Autowired AutomationWorker worker;

    private Map<String, Object> askFlow() {
        return flow(List.of(
                node("q", "ask_question", "AskName", Map.of("text", "Name?", "answerType", "TEXT")),
                node("m", "send_message", "Done", textMessage("Thanks"))), List.of(edge("q", "next", "m")));
    }

    private ResponseEntity<JsonNode> startTest(String chatbotId, String contactId, String phoneNumberId, Boolean stopRunning) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("contactId", contactId);
        if (phoneNumberId != null) body.put("phoneNumberId", phoneNumberId);
        if (stopRunning != null) body.put("stopRunning", stopRunning);
        return bots(HttpMethod.POST, "/" + chatbotId + "/test", manager, body);
    }

    @Test
    void testStartsOnPickedContact() throws Exception {
        WaWebChat chat = incoming(n1, VEENU, "chat", "hello", "Veenu");
        JsonNode bot = createChatbot("Bot", askFlow());
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            ResponseEntity<JsonNode> r = startTest(bot.get("id").asString(), chat.getContactId(), n1.getId(), null);
            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            JsonNode run = r.getBody();
            assertThat(run.get("test").asBoolean()).isTrue();
            assertThat(run.get("chat").get("id").asString()).isEqualTo(chat.getId());
            await(() -> runRepo.findById(run.get("id").asString()).orElseThrow().getStatus() == ChatbotRunStatus.WAITING);
            ChatbotRun stored = runRepo.findById(run.get("id").asString()).orElseThrow();
            assertThat(((Map<?, ?>) stored.getVariables().get("trigger")).get("ruleName")).isEqualTo("Test");
            assertThat(stored.getStartedBy().userId()).isEqualTo(manager.getId());
            assertThat(d.requests("sendMessage")).hasSize(1);
        }
    }

    @Test
    void testWithoutNumberResolvesChat() throws Exception {
        WaWebChat chat = incoming(n1, VEENU, "chat", "hello", "Veenu");
        JsonNode bot = createChatbot("Bot", askFlow());
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            ResponseEntity<JsonNode> r = startTest(bot.get("id").asString(), chat.getContactId(), null, null);
            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            String id = r.getBody().get("id").asString();
            await(() -> runRepo.findById(id).orElseThrow().getStatus() == ChatbotRunStatus.WAITING);
            assertThat(runRepo.findById(id).orElseThrow().getChatId()).isEqualTo(chat.getId());
        }
    }

    @Test
    void testRefusedWithErrors() {
        WaWebChat chat = incoming(n1, VEENU, "chat", "hello", "Veenu");
        JsonNode bot = createChatbot("Bot", flow(List.of(node("a", "wait", "A", Map.of("amount", 1, "unit", "HOURS")),
                node("b", "wait", "B", Map.of("amount", 1, "unit", "HOURS"))), List.of()));
        ResponseEntity<JsonNode> r = startTest(bot.get("id").asString(), chat.getContactId(), n1.getId(), null);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(r.getBody().get("fields").has("flow.entry")).isTrue();
        assertThat(runRepo.count()).isZero();
    }

    @Test
    void testRefusedWhenAnotherChatbotRunsThenStopAndStart() throws Exception {
        WaWebChat chat = incoming(n1, VEENU, "chat", "hello", "Veenu");
        JsonNode first = createChatbot("First", askFlow());
        JsonNode second = createChatbot("Second", askFlow());
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            String firstRun = startTest(first.get("id").asString(), chat.getContactId(), n1.getId(), null).getBody().get("id").asString();
            await(() -> runRepo.findById(firstRun).orElseThrow().getStatus() == ChatbotRunStatus.WAITING);
            ResponseEntity<JsonNode> refused = startTest(second.get("id").asString(), chat.getContactId(), n1.getId(), null);
            assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(refused.getBody().get("code").asString()).isEqualTo("CHATBOT_RUNNING");
            // CodedException extras are flattened into the error body
            assertThat(refused.getBody().get("chatbotName").asString()).isEqualTo("First");
            ResponseEntity<JsonNode> forced = startTest(second.get("id").asString(), chat.getContactId(), n1.getId(), true);
            assertThat(forced.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(runRepo.findById(firstRun).orElseThrow().getStatus()).isEqualTo(ChatbotRunStatus.STOPPED);
        }
    }

    @Test
    void secondTestStopsFirst() throws Exception {
        WaWebChat chat = incoming(n1, VEENU, "chat", "hello", "Veenu");
        JsonNode bot = createChatbot("Bot", askFlow());
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            String a = startTest(bot.get("id").asString(), chat.getContactId(), n1.getId(), null).getBody().get("id").asString();
            await(() -> runRepo.findById(a).orElseThrow().getStatus() == ChatbotRunStatus.WAITING);
            ResponseEntity<JsonNode> again = startTest(bot.get("id").asString(), chat.getContactId(), n1.getId(), null);
            assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(runRepo.findById(a).orElseThrow().getStatus()).isEqualTo(ChatbotRunStatus.STOPPED);
        }
    }

    @Test
    void draftNeverStartsOnLiveEvent() throws Exception {
        JsonNode draft = createChatbot("Draft bot", askFlow());
        // a rule saved directly may name a draft; at run time the action fails
        AutomationAction start = new AutomationAction(AutomationActionType.START_CHATBOT, null, null, null, null, null, null,
                null, null, null, draft.get("id").asString(), null);
        rule("Welcome", AutomationEventKind.MESSAGE_RECEIVED, RuleStatus.PUBLISHED, 1, null, start);
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            incoming(n1, VEENU, "chat", "hello", "Veenu");
            worker.tick();
            AutomationRun ruleRun = mongo.findAll(AutomationRun.class).get(0);
            assertThat(ruleRun.getSteps().get(0).outcome()).isEqualTo(StepOutcome.FAILED);
            assertThat(ruleRun.getSteps().get(0).reason()).contains("is not published");
            assertThat(runRepo.count()).isZero();
        }
    }

    @Test
    void viewerCannotTest() {
        WaWebChat chat = incoming(n1, VEENU, "chat", "hello", "Veenu");
        JsonNode bot = createChatbot("Bot", askFlow());
        ResponseEntity<JsonNode> r = bots(HttpMethod.POST, "/" + bot.get("id").asString() + "/test", agent,
                Map.of("contactId", chat.getContactId()));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
