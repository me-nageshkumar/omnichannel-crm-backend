package me.nageshk.omnichannel.app.chatbot;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import me.nageshk.omnichannel.app.automation.AutomationDevice;
import me.nageshk.omnichannel.db.chatbot.ChatbotRun;
import me.nageshk.omnichannel.db.chatbot.ChatbotRunRepository;
import me.nageshk.omnichannel.db.chatbot.ChatbotRunStatus;
import me.nageshk.omnichannel.db.chatbot.IfRunning;
import me.nageshk.omnichannel.db.chatbot.RunEnding;
import me.nageshk.omnichannel.db.chatbot.StartedBy;
import me.nageshk.omnichannel.db.contact.Contact;
import me.nageshk.omnichannel.db.contact.ContactRepository;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import me.nageshk.omnichannel.security.access.AccessService;
import me.nageshk.omnichannel.service.chatbot.run.ChatbotRunService;
import me.nageshk.omnichannel.service.chatbot.run.ChatbotTicker;
import me.nageshk.omnichannel.service.inbox.ChatAssignmentService;
import me.nageshk.omnichannel.service.inbox.ChatRoutingService;
import me.nageshk.omnichannel.service.inbox.InboxSettingService;
import me.nageshk.omnichannel.service.inbox.RoutingSetting;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import tools.jackson.databind.JsonNode;

/** Lead-owner report spec §6.2: a finished run records where its chat went. */
class RunEndingIT extends ChatbotTestBase {

    static final String VEENU = "919799750743@c.us";

    @Autowired ChatbotRunService runs;
    @Autowired ChatbotRunRepository runRepo;
    @Autowired ContactRepository contacts;
    @Autowired ChatbotTicker ticker;
    @Autowired InboxSettingService settings;
    @Autowired ChatAssignmentService assignment;
    @Autowired AccessService access;
    @MockitoSpyBean ChatRoutingService routing;

    @AfterEach
    void realRouting() {
        org.mockito.Mockito.reset(routing);
    }

    private static Map<String, Object> askFlow() {
        return flow(List.of(node("q", "ask_question", "AskName", Map.of("text", "Name?", "answerType", "TEXT"))), List.of());
    }

    private static Map<String, Object> sayFlow() {
        return flow(List.of(node("m", "send_message", "Hello", textMessage("Hello"))), List.of());
    }

    private static Map<String, Object> handOver(Map<String, Object> settings) {
        return flow(List.of(node("a", "assign_chat", "Hand", settings)), List.of());
    }

    /** The support team routes new chats to the agent. */
    private void routeToAgent() {
        settings.putOverride(brand.getId(), support.getId(),
                new RoutingSetting.Request(true, false, agent.getId(), null, null, null));
    }

    /** A chat with the contact in the support team's queue: open, nobody holds it. */
    private WaWebChat inQueue() {
        WaWebChat c = incoming(n1, VEENU, "chat", "hello", "Veenu");
        c = chats.findById(c.getId()).orElseThrow();
        c.setAssignedUserId(null);
        c.setAssignedTeamId(support.getId());
        return chats.save(c);
    }

    private String bot(String name, Map<String, Object> flow) {
        String id = createChatbot(name, flow).get("id").asString();
        assertThat(bots(HttpMethod.POST, "/" + id + "/publish", manager, null).getStatusCode().is2xxSuccessful()).isTrue();
        return id;
    }

    private ChatbotRun start(String chatbotId, WaWebChat chat) throws Exception {
        Contact c = contacts.findById(chat.getContactId()).orElseThrow();
        ChatbotRunService.StartResult r = runs.start(brand.getId(), chatbotId, c, chat, StartedBy.test(manager.getId()),
                null, IfRunning.SKIP, true);
        assertThat(r.started()).as(r.reason()).isTrue();
        r.settled().get(5, TimeUnit.SECONDS);
        return fresh(r.run());
    }

    private ChatbotRun fresh(ChatbotRun r) {
        return runRepo.findById(r.getId()).orElseThrow();
    }

    private RunEnding endingOf(ChatbotRun r) throws InterruptedException {
        await(() -> fresh(r).getEnding() != null);
        return fresh(r).getEnding();
    }

    @Test
    void handOverRecordsTheAgent() throws Exception {
        routeToAgent();
        WaWebChat c = inQueue();
        ChatbotRun r = start(bot("Hand", handOver(Map.of("mode", "NUMBER_ROUTING"))), c);
        assertThat(r.getStatus()).isEqualTo(ChatbotRunStatus.HANDED_OVER);
        RunEnding e = endingOf(r);
        assertThat(e.chat()).isEqualTo(RunEnding.Chat.HANDED_OVER);
        assertThat(e.agentUserId()).isEqualTo(agent.getId());
        assertThat(e.teamId()).isEqualTo(support.getId());
        assertThat(e.at()).isNotNull();
    }

    @Test
    void handOverToAQueueRecordsNoAgent() throws Exception {
        WaWebChat c = inQueue();
        ChatbotRun r = start(bot("Queue", handOver(Map.of("mode", "TEAM_QUEUE", "teamId", support.getId()))), c);
        RunEnding e = endingOf(r);
        assertThat(e.chat()).isEqualTo(RunEnding.Chat.HANDED_OVER);
        assertThat(e.agentUserId()).isNull();
        assertThat(e.teamId()).isEqualTo(support.getId());
    }

    @Test
    void completedAndClosedRecordsClosed() throws Exception {
        WaWebChat c = inQueue();
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            ChatbotRun r = start(bot("Say", sayFlow()), c);
            RunEnding e = endingOf(r);
            assertThat(fresh(r).getStatus()).isEqualTo(ChatbotRunStatus.COMPLETED);
            assertThat(e.chat()).isEqualTo(RunEnding.Chat.CLOSED);
            assertThat(e.agentUserId()).isNull();
        }
    }

    @Test
    void silentAndRoutedRecordsRouted() throws Exception {
        routeToAgent();
        WaWebChat c = inQueue();
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            ChatbotRun r = start(bot("Ask", askFlow()), c);
            clock.advance(Duration.ofMinutes(121));
            ticker.tick().get(5, TimeUnit.SECONDS);
            assertThat(fresh(r).getStatus()).isEqualTo(ChatbotRunStatus.ENDED);
            RunEnding e = endingOf(r);
            assertThat(e.chat()).isEqualTo(RunEnding.Chat.ROUTED);
            assertThat(e.agentUserId()).isEqualTo(agent.getId());
        }
    }

    @Test
    void jumpedOrReplacedRecordsLeft() throws Exception {
        WaWebChat c = inQueue();
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            String target = bot("Target", askFlow());
            start(bot("Router", flow(List.of(node("j", "jump", "Jump", Map.of("chatbotId", target))), List.of())), c);
            await(() -> runRepo.findAll().stream().anyMatch(r -> r.getStatus() == ChatbotRunStatus.WAITING));
            ChatbotRun first = runRepo.findAll().stream().filter(r -> "Router".equals(r.getChatbotName())).findFirst()
                    .orElseThrow();
            assertThat(first.getStatus()).isEqualTo(ChatbotRunStatus.JUMPED);
            assertThat(endingOf(first).chat()).isEqualTo(RunEnding.Chat.LEFT);
        }
    }

    @Test
    void agentTookTheChatRecordsLeft() throws Exception {
        WaWebChat c = inQueue();
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            ChatbotRun r = start(bot("Ask", askFlow()), c);
            assignment.assign(access.load(manager), chats.findById(c.getId()).orElseThrow(), agent.getId(), null);
            incoming(n1, VEENU, "chat", "Rahul", "Veenu");
            await(() -> fresh(r).getStatus() == ChatbotRunStatus.COMPLETED);
            RunEnding e = endingOf(r);
            assertThat(e.chat()).isEqualTo(RunEnding.Chat.LEFT);
        }
    }

    @Test
    void runViewShowsEnding() throws Exception {
        routeToAgent();
        WaWebChat c = inQueue();
        String id = bot("Hand", handOver(Map.of("mode", "NUMBER_ROUTING")));
        ChatbotRun r = start(id, c);
        endingOf(r);
        JsonNode view = bots(HttpMethod.GET, "/" + id + "/runs/" + r.getId(), manager, null).getBody();
        assertThat(view.get("ending").get("chat").asString()).isEqualTo("HANDED_OVER");
        assertThat(view.get("ending").get("agent").get("id").asString()).isEqualTo(agent.getId());
        assertThat(view.get("ending").get("agent").get("name").asString()).isEqualTo(agent.getName());
        assertThat(view.get("ending").get("team").get("name").asString()).isEqualTo(support.getName());
    }

    @Test
    void routingLostToSomeoneElseRecordsLeft() throws Exception {
        WaWebChat c = inQueue();
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            ChatbotRun r = start(bot("Ask", askFlow()), c);
            // a message lands during routing and a person takes the chat before the retry
            org.mockito.Mockito.doAnswer(call -> {
                WaWebChat held = chats.findById(c.getId()).orElseThrow();
                held.setAssignedUserId(agent.getId());
                chats.save(held);
                throw new OptimisticLockingFailureException("a message landed");
            }).when(routing).route(org.mockito.ArgumentMatchers.any(WaWebChat.class),
                    org.mockito.ArgumentMatchers.any(ChatRoutingService.RoutingOptions.class));
            runs.stop(r, "stopped by manager", manager.getId());
            RunEnding e = endingOf(r);
            assertThat(e.chat()).isEqualTo(RunEnding.Chat.LEFT);
        }
    }
}
