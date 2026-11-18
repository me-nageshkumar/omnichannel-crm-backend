package me.nageshk.omnichannel.app.chatbot;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import me.nageshk.omnichannel.app.automation.AutomationDevice;
import me.nageshk.omnichannel.db.chatbot.AfterChatbot;
import me.nageshk.omnichannel.db.chatbot.Chatbot;
import me.nageshk.omnichannel.db.chatbot.ChatbotRepository;
import me.nageshk.omnichannel.db.chatbot.ChatbotRun;
import me.nageshk.omnichannel.db.chatbot.ChatbotRunRepository;
import me.nageshk.omnichannel.db.chatbot.ChatbotRunStatus;
import me.nageshk.omnichannel.db.chatbot.ChatbotSettingsDocument;
import me.nageshk.omnichannel.db.chatbot.ChatbotSettingsLayer;
import me.nageshk.omnichannel.db.chatbot.IfRunning;
import me.nageshk.omnichannel.db.chatbot.StartedBy;
import me.nageshk.omnichannel.db.contact.Contact;
import me.nageshk.omnichannel.db.contact.ContactRepository;
import me.nageshk.omnichannel.db.inbox.ActionType;
import me.nageshk.omnichannel.db.inbox.ChatStatus;
import me.nageshk.omnichannel.db.inbox.RoutedReason;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import me.nageshk.omnichannel.db.inbox.WaWebChatAction;
import me.nageshk.omnichannel.security.auth.AuthenticatedUser;
import me.nageshk.omnichannel.security.access.AccessService;
import me.nageshk.omnichannel.service.chatbot.run.ChatbotRunService;
import me.nageshk.omnichannel.service.chatbot.run.ChatbotTicker;
import me.nageshk.omnichannel.service.chatbot.settings.ChatbotSettingsService;
import me.nageshk.omnichannel.service.inbox.ChatAssignmentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/** Spec 2026-11-18 §5.3: a run takes a chat nobody holds and, when it ends, closes it or gives it to the team. */
class BotReleaseIT extends ChatbotTestBase {

    static final String VEENU = "919799750743@c.us";

    @Autowired ChatbotRunService runs;
    @Autowired ChatbotRunRepository runRepo;
    @Autowired ChatbotRepository chatbots;
    @Autowired ContactRepository contacts;
    @Autowired ChatbotTicker ticker;
    @Autowired ChatbotSettingsService settingsService;
    @Autowired ChatAssignmentService assignment;
    @Autowired AccessService access;

    @Override
    protected List<Class<?>> collections() {
        List<Class<?>> c = new java.util.ArrayList<>(super.collections());
        c.add(ChatbotSettingsDocument.class);
        return c;
    }

    private static Map<String, Object> askFlow() {
        return flow(List.of(node("q", "ask_question", "AskName", Map.of("text", "Name?", "answerType", "TEXT"))), List.of());
    }

    private static Map<String, Object> sayFlow() {
        return flow(List.of(node("m", "send_message", "Hello", textMessage("Hello"))), List.of());
    }

    private static Map<String, Object> jumpFlow(String toChatbotId) {
        return flow(List.of(node("j", "jump", "Jump", Map.of("chatbotId", toChatbotId))), List.of());
    }

    /** A chat with the contact in the support team's queue: open, nobody holds it. */
    private WaWebChat inQueue() {
        WaWebChat c = incoming(n1, VEENU, "chat", "hello", "Veenu");
        c = chats.findById(c.getId()).orElseThrow();
        c.setAssignedUserId(null);
        c.setAssignedTeamId(support.getId());
        return chats.save(c);
    }

    private ChatbotRun start(String chatbotId, WaWebChat chat, IfRunning ifRunning) throws Exception {
        Contact c = contacts.findById(chat.getContactId()).orElseThrow();
        ChatbotRunService.StartResult r = runs.start(brand.getId(), chatbotId, c, chat, StartedBy.test(manager.getId()),
                null, ifRunning, true);
        assertThat(r.started()).as(r.reason()).isTrue();
        r.settled().get(5, TimeUnit.SECONDS);
        return runRepo.findById(r.run().getId()).orElseThrow();
    }

    private ChatbotRun start(String chatbotId, WaWebChat chat) throws Exception {
        return start(chatbotId, chat, IfRunning.SKIP);
    }

    private String bot(String name, Map<String, Object> flow) {
        return createChatbot(name, flow).get("id").asString();
    }

    private void chatbotSays(String chatbotId, AfterChatbot whenCompleted, AfterChatbot whenAbandoned) {
        Chatbot b = chatbots.findById(chatbotId).orElseThrow();
        b.setSettings(new ChatbotSettingsLayer(null, null, null, null, null, null, null, null, null, whenCompleted,
                whenAbandoned));
        chatbots.save(b);
    }

    private WaWebChat fresh(WaWebChat c) {
        return chats.findById(c.getId()).orElseThrow();
    }

    private ChatbotRun fresh(ChatbotRun r) {
        return runRepo.findById(r.getId()).orElseThrow();
    }

    private List<WaWebChatAction> actions(WaWebChat c, ActionType type) {
        return mongo.findAll(WaWebChatAction.class).stream()
                .filter(a -> a.getChatId().equals(c.getId()) && a.getType() == type).toList();
    }

    private void assertRoutedToTheTeam(WaWebChat c) {
        WaWebChat after = fresh(c);
        assertThat(after.getStatus()).isEqualTo(ChatStatus.OPEN);
        assertThat(after.getAssignedTeamId()).isEqualTo(support.getId());
        assertThat(after.getHandedOverAt()).isNotNull();
        List<WaWebChatAction> routed = actions(c, ActionType.ROUTED);
        assertThat(routed).isNotEmpty();
        assertThat(routed.get(routed.size() - 1).getReason()).isEqualTo(RoutedReason.CHATBOT_ENDED);
    }

    @Test
    void startOnAnOpenUnheldChatMakesItBot() throws Exception {
        WaWebChat c = inQueue();
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            start(bot("Welcome", askFlow()), c);
        }
        WaWebChat after = fresh(c);
        assertThat(after.getStatus()).isEqualTo(ChatStatus.BOT);
        assertThat(after.getAssignedTeamId()).isNull();
    }

    @Test
    void startOnAChatWithAnAgentLeavesItOpen() throws Exception {
        WaWebChat c = inQueue();
        c.setAssignedUserId(agent.getId());
        chats.save(c);
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            start(bot("Welcome", askFlow()), c);
        }
        WaWebChat after = fresh(c);
        assertThat(after.getStatus()).isEqualTo(ChatStatus.OPEN);
        assertThat(after.getAssignedUserId()).isEqualTo(agent.getId());
    }

    @Test
    void startOnAClosedChatLeavesItClosed() throws Exception {
        WaWebChat c = inQueue();
        c.setStatus(ChatStatus.CLOSED);
        chats.save(c);
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            start(bot("Welcome", askFlow()), c);
        }
        assertThat(fresh(c).getStatus()).isEqualTo(ChatStatus.CLOSED);
    }

    @Test
    void completedClosesByDefault() throws Exception {
        WaWebChat c = inQueue();
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            ChatbotRun r = start(bot("Welcome", sayFlow()), c);
            await(() -> fresh(r).getStatus() == ChatbotRunStatus.COMPLETED);
        }
        await(() -> fresh(c).getStatus() == ChatStatus.CLOSED);
        List<WaWebChatAction> closed = actions(c, ActionType.CLOSED);
        assertThat(closed).hasSize(1);
        assertThat(closed.get(0).getByName()).isEqualTo("Chatbot: Welcome");
        assertThat(actions(c, ActionType.ROUTED).stream().filter(a -> a.getReason() == RoutedReason.CHATBOT_ENDED))
                .isEmpty();
    }

    @Test
    void completedRoutesWhenTheChatbotSaysSo() throws Exception {
        WaWebChat c = inQueue();
        String id = bot("Welcome", sayFlow());
        chatbotSays(id, AfterChatbot.ROUTE, null);
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            ChatbotRun r = start(id, c);
            await(() -> fresh(r).getStatus() == ChatbotRunStatus.COMPLETED);
        }
        await(() -> fresh(c).getStatus() == ChatStatus.OPEN);
        assertRoutedToTheTeam(c);
    }

    @Test
    void abandonedGoesToTheTeamByDefault() throws Exception {
        WaWebChat c = inQueue();
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            start(bot("Welcome", askFlow()), c);
            clock.advance(Duration.ofMinutes(121));
            ticker.tick().get(5, TimeUnit.SECONDS);
        }
        assertThat(runRepo.findAll().get(0).getStatus()).isEqualTo(ChatbotRunStatus.ENDED);
        assertRoutedToTheTeam(c);
    }

    @Test
    void abandonedClosesWhenTheBrandSaysSo() throws Exception {
        settingsService.saveBrand(brand.getId(), new ChatbotSettingsLayer(null, null, null, null, null, null, null, null,
                null, null, AfterChatbot.CLOSE), null, manager.getId());
        WaWebChat c = inQueue();
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            start(bot("Welcome", askFlow()), c);
            clock.advance(Duration.ofMinutes(121));
            ticker.tick().get(5, TimeUnit.SECONDS);
        }
        assertThat(fresh(c).getStatus()).isEqualTo(ChatStatus.CLOSED);
    }

    @Test
    void failedGoesToTheTeam() throws Exception {
        WaWebChat c = inQueue();
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            String id = bot("Welcome", askFlow());
            ChatbotRun r = start(id, c);
            // the chatbot is deleted while it waits: the customer's answer fails the run (Review focus 2)
            chatbots.deleteById(id);
            incoming(n1, VEENU, "chat", "Rahul", "Veenu");
            await(() -> fresh(r).getStatus() == ChatbotRunStatus.FAILED);
        }
        await(() -> fresh(c).getStatus() == ChatStatus.OPEN);
        assertRoutedToTheTeam(c);
    }

    @Test
    void stoppedByAPersonGoesToTheTeam() throws Exception {
        WaWebChat c = inQueue();
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            ChatbotRun r = start(bot("Welcome", askFlow()), c);
            runs.stop(r, "stopped by manager", manager.getId());
        }
        assertRoutedToTheTeam(c);
    }

    @Test
    void deletedChatbotStillReleasesToTheTeam() throws Exception {
        WaWebChat c = inQueue();
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            String id = bot("Welcome", askFlow());
            ChatbotRun r = start(id, c);
            chatbots.deleteById(id);
            runs.stop(r, "stopped by manager", manager.getId());
        }
        assertRoutedToTheTeam(c);
    }

    @Test
    void replaceKeepsTheChatWithTheNewRun() throws Exception {
        WaWebChat c = inQueue();
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            start(bot("First", askFlow()), c);
            ChatbotRun second = start(bot("Second", askFlow()), c, IfRunning.REPLACE);
            WaWebChat after = fresh(c);
            assertThat(after.getStatus()).isEqualTo(ChatStatus.BOT);
            assertThat(after.getChatbot().runId()).isEqualTo(second.getId());
        }
        assertThat(actions(c, ActionType.ROUTED).stream().filter(a -> a.getReason() == RoutedReason.CHATBOT_ENDED))
                .isEmpty();
    }

    @Test
    void jumpKeepsTheChatWithTheNextRun() throws Exception {
        WaWebChat c = inQueue();
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            String target = bot("Target", askFlow());
            publish(target);
            start(bot("Router", jumpFlow(target)), c);
            await(() -> runRepo.findAll().stream().anyMatch(r -> r.getStatus() == ChatbotRunStatus.WAITING));
        }
        WaWebChat after = fresh(c);
        assertThat(after.getStatus()).isEqualTo(ChatStatus.BOT);
        assertThat(actions(c, ActionType.ROUTED).stream().filter(a -> a.getReason() == RoutedReason.CHATBOT_ENDED))
                .isEmpty();
    }

    @Test
    void jumpToAMissingChatbotGoesToTheTeam() throws Exception {
        WaWebChat c = inQueue();
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            ChatbotRun r = start(bot("Router", jumpFlow("no-such-chatbot")), c);
            await(() -> fresh(r).getStatus() == ChatbotRunStatus.FAILED);
        }
        await(() -> fresh(c).getStatus() == ChatStatus.OPEN);
        assertRoutedToTheTeam(c);
    }

    @Test
    void closingClearsTheHandOverMark() {
        WaWebChat c = inQueue();
        c.setHandedOverAt(clock.instant());
        c = chats.save(c);
        AuthenticatedUser me = access.load(manager);

        assignment.close(me, c);

        assertThat(fresh(c).getHandedOverAt()).isNull();
    }

    private void publish(String chatbotId) {
        assertThat(bots(org.springframework.http.HttpMethod.POST, "/" + chatbotId + "/publish", manager, null)
                .getStatusCode().is2xxSuccessful()).isTrue();
    }
}
