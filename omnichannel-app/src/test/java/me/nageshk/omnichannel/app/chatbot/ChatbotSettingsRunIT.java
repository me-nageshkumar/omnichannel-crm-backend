package me.nageshk.omnichannel.app.chatbot;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import me.nageshk.omnichannel.app.automation.AutomationDevice;
import me.nageshk.omnichannel.db.chatbot.ChatbotRun;
import me.nageshk.omnichannel.db.chatbot.ChatbotRunRepository;
import me.nageshk.omnichannel.db.chatbot.ChatbotRunStatus;
import me.nageshk.omnichannel.db.chatbot.ChatbotSettingsDocument;
import me.nageshk.omnichannel.db.chatbot.ChatbotSettingsLayer;
import me.nageshk.omnichannel.db.chatbot.IfRunning;
import me.nageshk.omnichannel.db.chatbot.StartedBy;
import me.nageshk.omnichannel.db.contact.Contact;
import me.nageshk.omnichannel.db.contact.ContactRepository;
import me.nageshk.omnichannel.db.inbox.MessageDirection;
import me.nageshk.omnichannel.db.inbox.MessageSource;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import me.nageshk.omnichannel.db.inbox.WaWebMessage;
import me.nageshk.omnichannel.service.chatbot.node.TaskAnswer;
import me.nageshk.omnichannel.service.chatbot.run.ChatbotRunService;
import me.nageshk.omnichannel.service.chatbot.run.ChatbotTicker;
import me.nageshk.omnichannel.service.chatbot.settings.ChatbotSettingsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import tools.jackson.databind.JsonNode;

/** Spec 2026-11-14 §4.4: runs resolve their settings through the brand and platform layers, fresh on every use. */
class ChatbotSettingsRunIT extends ChatbotTestBase {

    static final String VEENU = "919799750743@c.us";

    @Autowired ChatbotRunService runs;
    @Autowired ChatbotRunRepository runRepo;
    @Autowired ContactRepository contacts;
    @Autowired ChatbotTicker ticker;
    @Autowired ChatbotSettingsService settings;

    @Override
    protected List<Class<?>> collections() {
        List<Class<?>> c = new ArrayList<>(super.collections());
        c.add(ChatbotSettingsDocument.class);
        return c;
    }

    private static ChatbotSettingsLayer layer(Map<String, Object> v) {
        return new ChatbotSettingsLayer((Boolean) v.get("reminderOn"), (Integer) v.get("reminderAfterMinutes"),
                (String) v.get("reminderMessage"), (Integer) v.get("endAfterMinutes"), (String) v.get("endMessage"),
                (Integer) v.get("invalidReplyRetries"), (String) v.get("retryMessage"), (String) v.get("giveUpMessage"),
                (Boolean) v.get("stopOnAgentReply"), (me.nageshk.omnichannel.db.chatbot.AfterChatbot) v.get("whenCompleted"),
                (me.nageshk.omnichannel.db.chatbot.AfterChatbot) v.get("whenAbandoned"));
    }

    private void brandSets(Map<String, Object> own) {
        settings.saveBrand(brand.getId(), layer(own), settings.brand(brand.getId()).version(), manager.getId());
    }

    private static Map<String, Object> buttons() {
        return Map.of("message", Map.of("type", "interactive", "interactive", Map.of("type", "button",
                "body", Map.of("text", "Book a demo?"), "action", Map.of("buttons", List.of(
                        Map.of("type", "reply", "reply", Map.of("id", "yes", "title", "Yes")),
                        Map.of("type", "reply", "reply", Map.of("id", "no", "title", "No")))))));
    }

    private Map<String, Object> buttonFlow() {
        return flow(List.of(
                node("ask", "send_message", "Ask", buttons()),
                node("yes", "send_message", "Yes", textMessage("Great")),
                node("other", "send_message", "Other", textMessage("Hmm"))),
                List.of(edge("ask", "reply:yes", "yes"), edge("ask", "other", "other")));
    }

    private Map<String, Object> askFlow() {
        return flow(List.of(
                node("q", "ask_question", "AskName", Map.of("text", "Name?", "answerType", "TEXT")),
                node("m", "send_message", "Done", textMessage("Thanks"))), List.of(edge("q", "next", "m")));
    }

    private JsonNode withSettings(JsonNode bot, Map<String, Object> own) {
        Map<String, Object> save = new LinkedHashMap<>();
        save.put("flow", mapper.convertValue(bot.get("flow"), Map.class));
        save.put("settings", own);
        save.put("version", bot.get("version").asLong());
        return bots(HttpMethod.PUT, "/" + bot.get("id").asString(), manager, save).getBody().get("chatbot");
    }

    private String publish(JsonNode bot) {
        assertThat(bots(HttpMethod.POST, "/" + bot.get("id").asString() + "/publish", manager, null).getStatusCode()
                .is2xxSuccessful()).isTrue();
        return bot.get("id").asString();
    }

    private ChatbotRun run(String id) {
        return runRepo.findById(id).orElseThrow();
    }

    private ChatbotRun start(String chatbotId, WaWebChat chat) throws Exception {
        Contact c = contacts.findById(chat.getContactId()).orElseThrow();
        ChatbotRunService.StartResult r = runs.start(brand.getId(), chatbotId, c, chat, StartedBy.test(manager.getId()),
                null, IfRunning.SKIP, true);
        assertThat(r.started()).isTrue();
        r.settled().get(5, TimeUnit.SECONDS);
        return run(r.run().getId());
    }

    private static WaWebMessage inbound(WaWebChat chat, String body) {
        WaWebMessage m = new WaWebMessage();
        m.setId("in-" + System.nanoTime());
        m.setChatId(chat.getId());
        m.setWorkspaceId(chat.getWorkspaceId());
        m.setDirection(MessageDirection.IN);
        m.setSource(MessageSource.CONTACT);
        m.setType("text");
        m.setBody(body);
        m.setTimestamp(Instant.now());
        return m;
    }

    private void tick() throws Exception {
        ticker.tick().get(5, TimeUnit.SECONDS);
    }

    private String retryTextAfterAnUnmatchedReply(String chatbotId, WaWebChat chat) throws Exception {
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            ChatbotRun run = start(chatbotId, chat);
            assertThat(runs.answer(TaskAnswer.whatsApp(chat.getId(), inbound(chat, "maybe")))).isTrue();
            // the retry text, then the question again; the count is stored once the run waits again
            await(() -> d.requests("sendMessage").size() >= 3);
            await(() -> run(run.getId()).getInvalidReplies() == 1
                    && run(run.getId()).getStatus() == ChatbotRunStatus.WAITING);
            return d.requests("sendMessage").get(1).params().get("content").asString();
        }
    }

    @Test
    void brandRetryMessageReachesARunningChatbot() throws Exception {
        WaWebChat chat = incoming(n1, VEENU, "chat", "hello", "Veenu");
        String id = publish(createChatbot("Bot", buttonFlow()));
        brandSets(Map.of("retryMessage", "Brand retry"));

        assertThat(retryTextAfterAnUnmatchedReply(id, chat)).isEqualTo("Brand retry");
    }

    @Test
    void chatbotOverrideWinsOverBrand() throws Exception {
        WaWebChat chat = incoming(n1, VEENU, "chat", "hello", "Veenu");
        String id = publish(withSettings(createChatbot("Bot", buttonFlow()), Map.of("retryMessage", "Bot retry")));
        brandSets(Map.of("retryMessage", "Brand retry"));

        assertThat(retryTextAfterAnUnmatchedReply(id, chat)).isEqualTo("Bot retry");
    }

    @Test
    void brandCanTurnOffStopOnAgentReply() throws Exception {
        WaWebChat chat = incoming(n1, VEENU, "chat", "hello", "Veenu");
        String id = publish(createChatbot("Lead qualifier", askFlow()));
        brandSets(Map.of("stopOnAgentReply", false));
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            ChatbotRun run = start(id, chat);
            call(HttpMethod.POST, "/api/workspaces/" + brand.getId() + "/inbox/chats/" + chat.getId() + "/messages",
                    agent, Map.of("text", "Hi"));
            Thread.sleep(300);
            assertThat(run(run.getId()).getStatus()).isEqualTo(ChatbotRunStatus.WAITING);
        }
    }

    @Test
    void reminderTurnedOffAfterSchedulingIsNotSent() throws Exception {
        WaWebChat chat = incoming(n1, VEENU, "chat", "hello", "Veenu");
        String id = publish(createChatbot("Bot", askFlow()));
        brandSets(Map.of("reminderOn", true, "reminderAfterMinutes", 1, "reminderMessage", "Still there?"));
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            ChatbotRun run = start(id, chat);
            assertThat(run.getRemindAt()).isNotNull();
            brandSets(Map.of("reminderOn", false));
            clock.advance(Duration.ofMinutes(2));
            tick();
            Thread.sleep(200);
            assertThat(d.requests("sendMessage")).hasSize(1);
        }
    }

    @Test
    void reminderAtOrAfterTheEndIsNotSent() throws Exception {
        WaWebChat chat = incoming(n1, VEENU, "chat", "hello", "Veenu");
        String id = publish(withSettings(createChatbot("Bot", askFlow()), Map.of("endAfterMinutes", 10)));
        brandSets(Map.of("reminderOn", true, "reminderAfterMinutes", 10, "reminderMessage", "Still there?"));
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            ChatbotRun run = start(id, chat);
            clock.advance(Duration.ofMinutes(10));
            tick();
            await(() -> run(run.getId()).getStatus() == ChatbotRunStatus.ENDED);
            Thread.sleep(200);
            assertThat(d.requests("sendMessage")).extracting(r -> r.params().get("content").asString())
                    .doesNotContain("Still there?");
        }
    }
}
