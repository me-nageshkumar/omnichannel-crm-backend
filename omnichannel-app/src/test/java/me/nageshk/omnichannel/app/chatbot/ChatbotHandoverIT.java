package me.nageshk.omnichannel.app.chatbot;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import me.nageshk.omnichannel.app.automation.AutomationDevice;
import me.nageshk.omnichannel.db.chatbot.ChatbotRun;
import me.nageshk.omnichannel.db.chatbot.ChatbotRunRepository;
import me.nageshk.omnichannel.db.chatbot.ChatbotRunStatus;
import me.nageshk.omnichannel.db.chatbot.IfRunning;
import me.nageshk.omnichannel.db.chatbot.StartedBy;
import me.nageshk.omnichannel.db.contact.Contact;
import me.nageshk.omnichannel.db.contact.ContactRepository;
import me.nageshk.omnichannel.db.inbox.MessageDirection;
import me.nageshk.omnichannel.db.inbox.MessageSource;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import me.nageshk.omnichannel.db.inbox.WaWebMessage;
import me.nageshk.omnichannel.service.chatbot.run.ChatbotRunService;
import me.nageshk.omnichannel.service.inbox.InboxEvents;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/** Spec §5.4: an agent's console reply hands the chat over; the inbox shows the run and can stop it. */
class ChatbotHandoverIT extends ChatbotTestBase {

    static final String VEENU = "919799750743@c.us";

    @Autowired ChatbotRunService runs;
    @Autowired ChatbotRunRepository runRepo;
    @Autowired ContactRepository contacts;

    private Map<String, Object> askFlow() {
        return flow(List.of(
                node("q", "ask_question", "AskName", Map.of("text", "Name?", "answerType", "TEXT")),
                node("m", "send_message", "Done", textMessage("Thanks"))), List.of(edge("q", "next", "m")));
    }

    private JsonNode withSettings(JsonNode bot, Map<String, Object> changes) {
        Map<String, Object> settings = new LinkedHashMap<>(mapper.convertValue(bot.get("settings"), Map.class));
        settings.putAll(changes);
        Map<String, Object> save = new LinkedHashMap<>();
        save.put("flow", mapper.convertValue(bot.get("flow"), Map.class));
        save.put("settings", settings);
        save.put("version", bot.get("version").asLong());
        return bots(HttpMethod.PUT, "/" + bot.get("id").asString(), manager, save).getBody().get("chatbot");
    }

    private String publish(JsonNode bot) {
        assertThat(bots(HttpMethod.POST, "/" + bot.get("id").asString() + "/publish", manager, null).getStatusCode().is2xxSuccessful()).isTrue();
        return bot.get("id").asString();
    }

    private ChatbotRun start(String chatbotId, WaWebChat chat) throws Exception {
        Contact c = contacts.findById(chat.getContactId()).orElseThrow();
        ChatbotRunService.StartResult r = runs.start(brand.getId(), chatbotId, c, chat, StartedBy.test(manager.getId()),
                null, IfRunning.SKIP, true);
        assertThat(r.started()).isTrue();
        r.settled().get(5, TimeUnit.SECONDS);
        return runRepo.findById(r.run().getId()).orElseThrow();
    }

    private ChatbotRun run(String id) {
        return runRepo.findById(id).orElseThrow();
    }

    private String inboxChat(WaWebChat chat) {
        return "/api/workspaces/" + brand.getId() + "/inbox/chats/" + chat.getId();
    }

    @Test
    void consoleReplyHandsOver() throws Exception {
        WaWebChat chat = incoming(n1, VEENU, "chat", "hello", "Veenu");
        String id = publish(createChatbot("Lead qualifier", askFlow()));
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            ChatbotRun run = start(id, chat);
            ResponseEntity<JsonNode> sent = call(HttpMethod.POST, inboxChat(chat) + "/messages", agent, Map.of("text", "Hi, Rahul here"));
            assertThat(sent.getStatusCode()).isEqualTo(HttpStatus.OK);
            await(() -> run(run.getId()).getStatus() == ChatbotRunStatus.HANDED_OVER);
            assertThat(run(run.getId()).getStoppedBy()).isEqualTo(agent.getId());
            JsonNode view = call(HttpMethod.GET, inboxChat(chat), agent, null).getBody();
            assertThat(view.get("chatbot").isNull()).isTrue();
        }
    }

    @Test
    void ownEchoDoesNotHandOver() throws Exception {
        WaWebChat chat = incoming(n1, VEENU, "chat", "hello", "Veenu");
        String id = publish(createChatbot("Lead qualifier", askFlow()));
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            ChatbotRun run = start(id, chat);
            WaWebMessage echo = new WaWebMessage();
            echo.setId("echo-1");
            echo.setChatId(chat.getId());
            echo.setWorkspaceId(brand.getId());
            echo.setDirection(MessageDirection.OUT);
            echo.setSource(MessageSource.WHATSAPP_WEB);
            echo.setType("text");
            echo.setBody("Name?");
            echo.setTimestamp(Instant.now());
            publisher.publishEvent(new InboxEvents.MessageUpserted(chats.findById(chat.getId()).orElseThrow(), echo, true));
            Thread.sleep(300);
            assertThat(run(run.getId()).getStatus()).isEqualTo(ChatbotRunStatus.WAITING);
        }
    }

    @Test
    void settingOffKeepsRunning() throws Exception {
        WaWebChat chat = incoming(n1, VEENU, "chat", "hello", "Veenu");
        String id = publish(withSettings(createChatbot("Lead qualifier", askFlow()), Map.of("stopOnAgentReply", false)));
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            ChatbotRun run = start(id, chat);
            call(HttpMethod.POST, inboxChat(chat) + "/messages", agent, Map.of("text", "Hi"));
            Thread.sleep(300);
            assertThat(run(run.getId()).getStatus()).isEqualTo(ChatbotRunStatus.WAITING);
        }
    }

    @Test
    void chatViewShowsRunningChatbot() throws Exception {
        WaWebChat chat = incoming(n1, VEENU, "chat", "hello", "Veenu");
        String id = publish(createChatbot("Lead qualifier", askFlow()));
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            ChatbotRun run = start(id, chat);
            JsonNode view = call(HttpMethod.GET, inboxChat(chat), agent, null).getBody();
            assertThat(view.get("chatbot").get("runId").asString()).isEqualTo(run.getId());
            assertThat(view.get("chatbot").get("name").asString()).isEqualTo("Lead qualifier");
            assertThat(view.get("chatbot").get("nodeName").asString()).isEqualTo("AskName");
            assertThat(view.get("chatbot").get("waitingFor").asString()).isEqualTo("WhatsApp reply");
        }
    }

    @Test
    void stopFromInbox() throws Exception {
        WaWebChat chat = incoming(n1, VEENU, "chat", "hello", "Veenu");
        String id = publish(createChatbot("Lead qualifier", askFlow()));
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            ChatbotRun run = start(id, chat);
            ResponseEntity<JsonNode> stop = call(HttpMethod.POST, inboxChat(chat) + "/chatbot/stop", agent, null);
            assertThat(stop.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
            ChatbotRun after = run(run.getId());
            assertThat(after.getStatus()).isEqualTo(ChatbotRunStatus.STOPPED);
            assertThat(after.getStoppedBy()).isEqualTo(agent.getId());
            assertThat(after.getEndReason()).contains("stopped by");
            assertThat(call(HttpMethod.GET, inboxChat(chat), agent, null).getBody().get("chatbot").isNull()).isTrue();
            ResponseEntity<JsonNode> again = call(HttpMethod.POST, inboxChat(chat) + "/chatbot/stop", agent, null);
            assertThat(again.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(again.getBody().get("code").asString()).isEqualTo("NO_CHATBOT");
        }
    }

    @Test
    void agentWithoutSendCannotStop() throws Exception {
        WaWebChat chat = incoming(n1, VEENU, "chat", "hello", "Veenu");
        String id = publish(createChatbot("Lead qualifier", askFlow()));
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            start(id, chat);
            // the outsider is an agent of the brand without access to n1's team: the chat is not visible to them
            ResponseEntity<JsonNode> stop = call(HttpMethod.POST, inboxChat(chat) + "/chatbot/stop", outsider, null);
            assertThat(stop.getStatusCode()).isIn(HttpStatus.FORBIDDEN, HttpStatus.NOT_FOUND);
        }
    }
}
