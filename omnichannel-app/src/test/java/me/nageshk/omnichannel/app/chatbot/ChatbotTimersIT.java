package me.nageshk.omnichannel.app.chatbot;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
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
import me.nageshk.omnichannel.db.chatbot.TaskKind;
import me.nageshk.omnichannel.db.contact.Contact;
import me.nageshk.omnichannel.db.contact.ContactRepository;
import me.nageshk.omnichannel.db.inbox.MessageDirection;
import me.nageshk.omnichannel.db.inbox.MessageSource;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import me.nageshk.omnichannel.db.inbox.WaWebMessage;
import me.nageshk.omnichannel.service.chatbot.node.TaskAnswer;
import me.nageshk.omnichannel.service.chatbot.run.ChatbotRunService;
import me.nageshk.omnichannel.service.chatbot.run.ChatbotTicker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import tools.jackson.databind.JsonNode;

/** Spec §5.6: reminders, inactivity ends, Wait timers and stale runs, all driven by the ticker and a movable clock. */
class ChatbotTimersIT extends ChatbotTestBase {

    static final String VEENU = "919799750743@c.us";

    @Autowired ChatbotRunService runs;
    @Autowired ChatbotRunRepository runRepo;
    @Autowired ContactRepository contacts;
    @Autowired ChatbotTicker ticker;

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

    private void tick() throws Exception {
        ticker.tick().get(5, TimeUnit.SECONDS);
    }

    @Test
    void reminderSentOnce() throws Exception {
        WaWebChat chat = incoming(n1, VEENU, "chat", "hello", "Veenu");
        String id = publish(withSettings(createChatbot("Bot", askFlow()),
                Map.of("reminderAfterMinutes", 5, "reminderMessage", "Still there?")));
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            ChatbotRun run = start(id, chat);
            assertThat(run.getRemindAt()).isEqualTo(clock.instant().plus(Duration.ofMinutes(5)));
            assertThat(run.getEndAt()).isEqualTo(clock.instant().plus(Duration.ofMinutes(120)));
            clock.advance(Duration.ofMinutes(4));
            tick();
            assertThat(d.requests("sendMessage")).hasSize(1);
            clock.advance(Duration.ofMinutes(1));
            tick();
            await(() -> d.requests("sendMessage").size() == 2);
            assertThat(d.requests("sendMessage").get(1).params().get("content").asString()).isEqualTo("Still there?");
            assertThat(run(run.getId()).isReminderSent()).isTrue();
            clock.advance(Duration.ofMinutes(5));
            tick();
            Thread.sleep(200);
            assertThat(d.requests("sendMessage")).hasSize(2);
            assertThat(run(run.getId()).getStatus()).isEqualTo(ChatbotRunStatus.WAITING);
        }
    }

    @Test
    void inactivityEndsRun() throws Exception {
        WaWebChat chat = incoming(n1, VEENU, "chat", "hello", "Veenu");
        String id = publish(withSettings(createChatbot("Bot", askFlow()), Map.of("endAfterMinutes", 30, "endMessage", "Bye for now")));
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            ChatbotRun run = start(id, chat);
            clock.advance(Duration.ofMinutes(31));
            tick();
            await(() -> run(run.getId()).getStatus() == ChatbotRunStatus.ENDED);
            assertThat(run(run.getId()).getEndReason()).isEqualTo("no reply for 30 minutes");
            assertThat(d.requests("sendMessage").get(1).params().get("content").asString()).isEqualTo("Bye for now");
            assertThat(chats.findById(chat.getId()).orElseThrow().getChatbot()).isNull();
        }
    }

    @Test
    void replyPushesTimersForward() throws Exception {
        WaWebChat chat = incoming(n1, VEENU, "chat", "hello", "Veenu");
        Map<String, Object> f = flow(List.of(
                node("q", "ask_question", "AskName", Map.of("text", "Name?", "answerType", "TEXT")),
                node("q2", "ask_question", "AskCity", Map.of("text", "City?", "answerType", "TEXT"))), List.of(edge("q", "next", "q2")));
        String id = publish(withSettings(createChatbot("Bot", f), Map.of("endAfterMinutes", 60)));
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            ChatbotRun run = start(id, chat);
            Instant firstEnd = run.getEndAt();
            clock.advance(Duration.ofMinutes(50));
            incoming(n1, VEENU, "chat", "Rahul", "Veenu");
            await(() -> run(run.getId()).getCurrentNodeName().equals("AskCity") && run(run.getId()).getStatus() == ChatbotRunStatus.WAITING);
            assertThat(run(run.getId()).getEndAt()).isEqualTo(firstEnd.plus(Duration.ofMinutes(50)));
            clock.advance(Duration.ofMinutes(15));
            tick();
            Thread.sleep(200);
            assertThat(run(run.getId()).getStatus()).isEqualTo(ChatbotRunStatus.WAITING);
            clock.advance(Duration.ofMinutes(50));
            tick();
            await(() -> run(run.getId()).getStatus() == ChatbotRunStatus.ENDED);
        }
    }

    @Test
    void waitResumesOnTick() throws Exception {
        WaWebChat chat = incoming(n1, VEENU, "chat", "hello", "Veenu");
        Map<String, Object> f = flow(List.of(
                node("w", "wait", "Pause", Map.of("amount", 2, "unit", "HOURS")),
                node("m", "send_message", "Later", textMessage("Hello again"))), List.of(edge("w", null, "m")));
        String id = publish(createChatbot("Bot", f));
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            ChatbotRun run = start(id, chat);
            assertThat(run.getStatus()).isEqualTo(ChatbotRunStatus.WAITING);
            assertThat(run.getWaiting().kind()).isEqualTo(TaskKind.TIMER);
            assertThat(run.getRemindAt()).isNull();
            assertThat(run.getEndAt()).isNull();
            clock.advance(Duration.ofHours(1));
            tick();
            Thread.sleep(200);
            assertThat(d.requests("sendMessage")).isEmpty();
            clock.advance(Duration.ofHours(1));
            tick();
            await(() -> run(run.getId()).getStatus() == ChatbotRunStatus.COMPLETED);
            assertThat(d.requests("sendMessage").get(0).params().get("content").asString()).isEqualTo("Hello again");
            assertThat(run(run.getId()).getSteps().get(0).outcome().name()).isEqualTo("DONE");
        }
    }

    @Test
    void replyDuringWaitIsIgnored() throws Exception {
        WaWebChat chat = incoming(n1, VEENU, "chat", "hello", "Veenu");
        Map<String, Object> f = flow(List.of(
                node("w", "wait", "Pause", Map.of("amount", 2, "unit", "HOURS")),
                node("m", "send_message", "Later", textMessage("Hello again"))), List.of(edge("w", null, "m")));
        String id = publish(createChatbot("Bot", f));
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            ChatbotRun run = start(id, chat);
            WaWebMessage m = new WaWebMessage();
            m.setId("in-1");
            m.setChatId(chat.getId());
            m.setDirection(MessageDirection.IN);
            m.setSource(MessageSource.CONTACT);
            m.setType("text");
            m.setBody("hello?");
            assertThat(runs.answer(TaskAnswer.whatsApp(chat.getId(), m))).isFalse();
            incoming(n1, VEENU, "chat", "anyone?", "Veenu");
            Thread.sleep(300);
            assertThat(run(run.getId()).getStatus()).isEqualTo(ChatbotRunStatus.WAITING);
            assertThat(run(run.getId()).getSteps()).hasSize(1);
            clock.advance(Duration.ofHours(2));
            tick();
            await(() -> run(run.getId()).getStatus() == ChatbotRunStatus.COMPLETED);
        }
    }

    @Test
    void staleRunningRunFails() throws Exception {
        WaWebChat chat = incoming(n1, VEENU, "chat", "hello", "Veenu");
        ChatbotRun stale = new ChatbotRun();
        stale.setCustomerId(acme.getId());
        stale.setWorkspaceId(brand.getId());
        stale.setChatbotId("c-gone");
        stale.setChatbotName("Gone");
        stale.setContactId(chat.getContactId());
        stale.setChatId(chat.getId());
        stale.setStatus(ChatbotRunStatus.RUNNING);
        stale.setLeaseUntil(clock.instant().minus(Duration.ofMinutes(1)));
        stale.setCreatedAt(clock.instant().minus(Duration.ofMinutes(5)));
        stale = runRepo.insert(stale);
        chat.setChatbot(new WaWebChat.ActiveChatbot(stale.getId(), "c-gone", "Gone", "X"));
        chats.save(chat);
        tick();
        ChatbotRun after = run(stale.getId());
        assertThat(after.getStatus()).isEqualTo(ChatbotRunStatus.FAILED);
        assertThat(after.getEndReason()).isEqualTo("interrupted");
        assertThat(chats.findById(chat.getId()).orElseThrow().getChatbot()).isNull();
    }
}
