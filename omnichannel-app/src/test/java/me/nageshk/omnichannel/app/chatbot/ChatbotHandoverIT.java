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
import me.nageshk.omnichannel.db.chatbot.TaskKind;
import me.nageshk.omnichannel.service.chatbot.node.TaskAnswer;
import me.nageshk.omnichannel.service.chatbot.run.ChatPointer;
import me.nageshk.omnichannel.service.chatbot.run.ChatbotRunService;
import me.nageshk.omnichannel.service.chatbot.run.ChatbotRunner;
import me.nageshk.omnichannel.service.chatbot.run.RunClaims;
import org.springframework.dao.OptimisticLockingFailureException;
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
    @Autowired RunClaims claims;
    @Autowired ChatbotRunner runner;
    @Autowired ChatPointer pointer;

    private Map<String, Object> twoAsksFlow() {
        return flow(List.of(
                node("q1", "ask_question", "AskName", Map.of("text", "Name?", "answerType", "TEXT")),
                node("q2", "ask_question", "AskCity", Map.of("text", "City?", "answerType", "TEXT"))), List.of(edge("q1", "next", "q2")));
    }

    private WaWebMessage reply(WaWebChat chat, String body) {
        WaWebMessage m = new WaWebMessage();
        m.setId("in-" + body);
        m.setChatId(chat.getId());
        m.setWorkspaceId(brand.getId());
        m.setDirection(MessageDirection.IN);
        m.setSource(MessageSource.CONTACT);
        m.setType("chat");
        m.setBody(body);
        m.setTimestamp(Instant.now());
        return m;
    }

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
    /** Review fix: a Stop that holds a copy from before the run moved on still stops it (no optimistic-lock failure). */
    @Test
    void stopWithStaleCopyStillStops() throws Exception {
        WaWebChat chat = incoming(n1, VEENU, "chat", "hello", "Veenu");
        String id = publish(createChatbot("Lead qualifier", twoAsksFlow()));
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            ChatbotRun stale = start(id, chat);
            assertThat(runs.answer(TaskAnswer.whatsApp(chat.getId(), reply(chat, "Rahul")))).isTrue();
            await(() -> "AskCity".equals(run(stale.getId()).getCurrentNodeName()) && run(stale.getId()).getStatus() == ChatbotRunStatus.WAITING);
            ChatbotRun stopped = runs.stop(stale, "stopped by the test", agent.getId());
            assertThat(stopped.getStatus()).isEqualTo(ChatbotRunStatus.STOPPED);
            assertThat(run(stale.getId()).getStatus()).isEqualTo(ChatbotRunStatus.STOPPED);
            assertThat(run(stale.getId()).getStoppedBy()).isEqualTo(agent.getId());
            assertThat(chats.findById(chat.getId()).orElseThrow().getChatbot()).isNull();
        }
    }

    /** Review fix: a run stopped while its runner holds a claimed copy stays stopped; the runner neither saves nor throws. */
    @Test
    void runnerDoesNotResurrectAStoppedRun() throws Exception {
        WaWebChat chat = incoming(n1, VEENU, "chat", "hello", "Veenu");
        String id = publish(createChatbot("Lead qualifier", twoAsksFlow()));
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            ChatbotRun waiting = start(id, chat);
            ChatbotRun claimed = claims.claimForAnswer(TaskKind.WHATSAPP_REPLY, chat.getId()).orElseThrow();
            runs.stop(run(waiting.getId()), "stopped by the test", agent.getId());
            assertThat(run(waiting.getId()).getStatus()).isEqualTo(ChatbotRunStatus.STOPPED);
            ChatbotRun after = runner.proceed(claimed, TaskAnswer.whatsApp(chat.getId(), reply(chat, "Rahul")));
            assertThat(after.getStatus()).isEqualTo(ChatbotRunStatus.STOPPED);
            assertThat(run(waiting.getId()).getStatus()).isEqualTo(ChatbotRunStatus.STOPPED);
            assertThat(run(waiting.getId()).getCurrentNodeName()).isEqualTo("AskName");
            assertThat(chats.findById(chat.getId()).orElseThrow().getChatbot()).isNull();
        }
    }

    /** Review fix: setting the pointer bumps the chat's version, so a stale full save of the chat cannot wipe it. */
    @Test
    void pointerSurvivesStaleChatSave() throws Exception {
        WaWebChat chat = incoming(n1, VEENU, "chat", "hello", "Veenu");
        String id = publish(createChatbot("Lead qualifier", askFlow()));
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            ChatbotRun run = start(id, chat);
            WaWebChat before = chats.findById(chat.getId()).orElseThrow();
            pointer.set(chat.getId(), run);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> chats.save(before)).isInstanceOf(OptimisticLockingFailureException.class);
            assertThat(chats.findById(chat.getId()).orElseThrow().getChatbot().runId()).isEqualTo(run.getId());
        }
    }

    /** Review fix: a pointer left behind at a finished run is not shown and is cleared on read. */
    @Test
    void staleChatPointerIsHidden() throws Exception {
        WaWebChat chat = incoming(n1, VEENU, "chat", "hello", "Veenu");
        String id = publish(createChatbot("Lead qualifier", askFlow()));
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            ChatbotRun run = start(id, chat);
            runs.stop(run(run.getId()), "stopped by the test", agent.getId());
            pointer.set(chat.getId(), run(run.getId()));
            assertThat(chats.findById(chat.getId()).orElseThrow().getChatbot()).isNotNull();
            JsonNode view = call(HttpMethod.GET, inboxChat(chat), agent, null).getBody();
            assertThat(view.get("chatbot").isNull()).isTrue();
            assertThat(chats.findById(chat.getId()).orElseThrow().getChatbot()).isNull();
        }
    }
}
