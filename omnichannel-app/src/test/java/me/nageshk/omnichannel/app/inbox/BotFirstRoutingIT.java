package me.nageshk.omnichannel.app.inbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import me.nageshk.omnichannel.app.automation.AutomationDevice;
import me.nageshk.omnichannel.app.chatbot.ChatbotTestBase;
import me.nageshk.omnichannel.app.support.TestBilling;
import me.nageshk.omnichannel.db.automation.AssignMode;
import me.nageshk.omnichannel.db.automation.AutomationAction;
import me.nageshk.omnichannel.db.automation.AutomationActionType;
import me.nageshk.omnichannel.db.automation.AutomationEventKind;
import me.nageshk.omnichannel.db.automation.RuleStatus;
import me.nageshk.omnichannel.db.chatbot.ChatbotRunRepository;
import me.nageshk.omnichannel.db.chatbot.ChatbotRunStatus;
import me.nageshk.omnichannel.db.chatbot.IfRunning;
import me.nageshk.omnichannel.db.chatbot.StartedBy;
import me.nageshk.omnichannel.db.contact.ContactRepository;
import me.nageshk.omnichannel.db.inbox.ActionType;
import me.nageshk.omnichannel.db.inbox.ChatStatus;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import me.nageshk.omnichannel.db.inbox.WaWebChatAction;
import me.nageshk.omnichannel.db.tenant.Team;
import me.nageshk.omnichannel.service.automation.AutomationWorker;
import me.nageshk.omnichannel.service.chatbot.run.ChatbotRunService;
import me.nageshk.omnichannel.service.inbox.BotChats;
import me.nageshk.omnichannel.service.inbox.InboxSettingService;
import me.nageshk.omnichannel.service.inbox.RoutingGate;
import me.nageshk.omnichannel.service.inbox.RoutingSetting;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;

/** Spec 2026-11-18 §5.2: a new chat waits for its message's rules, so a chatbot gets it before any person. */
class BotFirstRoutingIT extends ChatbotTestBase {

    /** The chat rows the live streams are given, in order. */
    static class ChatChanges {
        final List<WaWebChat> changes = new java.util.concurrent.CopyOnWriteArrayList<>();

        @org.springframework.context.event.EventListener
        void on(me.nageshk.omnichannel.service.inbox.InboxEvents.ChatChanged e) {
            changes.add(e.chat());
        }
    }

    @org.springframework.boot.test.context.TestConfiguration
    static class ChangesConfig {
        @org.springframework.context.annotation.Bean
        ChatChanges botFirstChatChanges() {
            return new ChatChanges();
        }
    }

    @Autowired ChatChanges changes;

    static final String VEENU = "919799750743@c.us";

    @Autowired AutomationWorker worker;
    @Autowired RoutingGate gate;
    @Autowired BotChats botChats;
    @Autowired InboxSettingService settings;
    @Autowired ChatbotRunRepository runRepo;
    @Autowired ChatbotRunService runs;
    @Autowired ContactRepository contacts;
    @Autowired TestBilling billing;

    @BeforeEach
    void roundRobin() {
        billing.reset();
        settings.putOverride(brand.getId(), support.getId(), new RoutingSetting.Request(true, true, null, null, null, null));
    }

    @AfterEach
    void noPlan() {
        billing.reset();
    }

    private static Map<String, Object> askFlow() {
        return flow(List.of(node("q", "ask_question", "AskName", Map.of("text", "Name?", "answerType", "TEXT"))), List.of());
    }

    private String publishedChatbot() {
        String id = createChatbot("Welcome", askFlow()).get("id").asString();
        assertThat(bots(HttpMethod.POST, "/" + id + "/publish", manager, null).getStatusCode().is2xxSuccessful()).isTrue();
        return id;
    }

    private static AutomationAction startAction(String chatbotId) {
        return new AutomationAction(AutomationActionType.START_CHATBOT, null, null, null, null, null, null, null, null, null,
                chatbotId, IfRunning.SKIP);
    }

    private void chatbotRule(String chatbotId, String onlyWhenTextContains) {
        rule("Welcome", AutomationEventKind.MESSAGE_RECEIVED, RuleStatus.PUBLISHED, 10,
                onlyWhenTextContains == null ? null : all(leaf("message.text", "contains", onlyWhenTextContains)),
                startAction(chatbotId));
    }

    private WaWebChat fresh(WaWebChat c) {
        return chats.findById(c.getId()).orElseThrow();
    }

    private List<WaWebChatAction> routed(WaWebChat c) {
        return mongo.findAll(WaWebChatAction.class).stream()
                .filter(a -> a.getChatId().equals(c.getId()) && a.getType() == ActionType.ROUTED).toList();
    }

    private String cursor() {
        return teams.findById(support.getId()).map(Team::getRoundRobinCursor).orElse(null);
    }

    @Test
    void withoutAChatbotRuleANewChatIsRoutedAtOnce() {
        WaWebChat c = fresh(incoming(n1, VEENU, "chat", "hello", "Veenu"));

        assertThat(c.getAssignedUserId()).isNotNull();
        assertThat(c.getRoutingPendingSince()).isNull();
    }

    @Test
    void withAChatbotRuleTheChatWaitsThenTheChatbotTakesIt() throws Exception {
        chatbotRule(publishedChatbot(), null);
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            WaWebChat c = fresh(incoming(n1, VEENU, "chat", "hello", "Veenu"));
            assertThat(c.getAssignedUserId()).isNull();
            assertThat(c.getRoutingPendingSince()).isNotNull();

            worker.tick();
            await(() -> runRepo.findAll().stream().anyMatch(r -> r.getStatus() == ChatbotRunStatus.WAITING));

            WaWebChat after = fresh(c);
            assertThat(after.getStatus()).isEqualTo(ChatStatus.BOT);
            assertThat(after.getAssignedUserId()).isNull();
            assertThat(after.getRoutingPendingSince()).isNull();
            assertThat(routed(c)).isEmpty();
            assertThat(cursor()).isNull();
        }
    }

    @Test
    void aRuleThatDoesNotMatchRoutesAfterTheTick() {
        chatbotRule(publishedChatbot(), "price");
        WaWebChat c = fresh(incoming(n1, VEENU, "chat", "hello", "Veenu"));
        assertThat(c.getAssignedUserId()).isNull();

        worker.tick();

        WaWebChat after = fresh(c);
        assertThat(after.getStatus()).isEqualTo(ChatStatus.OPEN);
        assertThat(after.getAssignedUserId()).isNotNull();
        assertThat(after.getRoutingPendingSince()).isNull();
        assertThat(routed(c)).hasSize(1);
    }

    @Test
    void anEventKeptForARetryStillRoutesTheChat() {
        String t = template("Hi", "Hello");
        // no device: the send waits for a retry, and the rules after it wait too
        // a higher priority runs first
        rule("Greet", AutomationEventKind.MESSAGE_RECEIVED, RuleStatus.PUBLISHED, 20, null,
                new AutomationAction(AutomationActionType.SEND_TEMPLATE, t, Map.of(), null, null, null, null, null, null,
                        null, null, null));
        chatbotRule(publishedChatbot(), null);
        WaWebChat c = fresh(incoming(n1, VEENU, "chat", "hello", "Veenu"));

        worker.tick();

        assertThat(fresh(c).getAssignedUserId()).isNotNull();
        assertThat(queued()).hasSize(1);
    }

    @Test
    void aMessageThatQueuesNoEventIsRoutedAtOnce() {
        chatbotRule(publishedChatbot(), null);

        WaWebChat c = fresh(incoming(n1, VEENU, "call_log", null, "Veenu"));

        assertThat(c.getAssignedUserId()).isNotNull();
        assertThat(c.getRoutingPendingSince()).isNull();
        assertThat(queued()).isEmpty();
    }

    @Test
    void theLastRowTheStreamsGetIsTheRoutedOne() {
        chatbotRule(publishedChatbot(), null);
        changes.changes.clear();

        WaWebChat c = fresh(incoming(n1, VEENU, "call_log", null, "Veenu"));

        WaWebChat last = changes.changes.stream().filter(x -> x.getId().equals(c.getId())).reduce((a, b) -> b).orElseThrow();
        assertThat(c.getAssignedUserId()).isNotNull();
        assertThat(last.getAssignedUserId()).isEqualTo(c.getAssignedUserId());
    }

    @Autowired me.nageshk.omnichannel.service.automation.AssignChatHandler assignHandler;

    @Test
    void anAssignChatRuleOnAChatbotChatOpensItForTheAgent() throws Exception {
        String id = publishedChatbot();
        chatbotRule(id, null);
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            WaWebChat c = fresh(incoming(n1, VEENU, "chat", "hello", "Veenu"));
            worker.tick();
            await(() -> fresh(c).getStatus() == ChatStatus.BOT);
            me.nageshk.omnichannel.db.automation.EventData data = new me.nageshk.omnichannel.db.automation.EventData(
                    new me.nageshk.omnichannel.db.automation.EventData.ChatData(c.getId(), false, ChatStatus.BOT, null,
                            null, true), null, null, null, null, null, null, null);

            assignHandler.run(new me.nageshk.omnichannel.service.automation.ActionContext(brand.getId(), acme.getId(),
                    AutomationEventKind.MESSAGE_RECEIVED, "m-77", data, "r9", "Assign rule", false),
                    new AutomationAction(AutomationActionType.ASSIGN_CHAT, null, null, AssignMode.AGENT, agent2.getId(),
                            null, null, null, null, null, null, null));

            WaWebChat after = fresh(c);
            assertThat(after.getStatus()).isEqualTo(ChatStatus.OPEN);
            assertThat(after.getAssignedUserId()).isEqualTo(agent2.getId());
            assertThat(after.getHandedOverAt()).isNotNull();
        }
    }

    @Test
    void aReopenedChatWaitsToo() throws Exception {
        WaWebChat c = fresh(incoming(n1, VEENU, "chat", "hello", "Veenu"));
        c.setStatus(ChatStatus.CLOSED);
        c.setAssignedUserId(null);
        c.setAssignedTeamId(null);
        chats.save(c);
        chatbotRule(publishedChatbot(), null);
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            incoming(n1, VEENU, "chat", "again", "Veenu");
            assertThat(fresh(c).getRoutingPendingSince()).isNotNull();

            worker.tick();
            await(() -> fresh(c).getStatus() == ChatStatus.BOT);
        }
    }

    @Test
    void aChatbotThatWroteFirstTakesTheChatWhenTheCustomerAnswers() throws Exception {
        WaWebChat c = fresh(incoming(n1, VEENU, "chat", "hello", "Veenu"));
        c.setStatus(ChatStatus.CLOSED);
        c.setAssignedUserId(null);
        c.setAssignedTeamId(null);
        c = chats.save(c);
        String id = publishedChatbot();
        int routedBefore = routed(c).size();
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            ChatbotRunService.StartResult r = runs.start(brand.getId(), id, contacts.findById(c.getContactId()).orElseThrow(),
                    c, StartedBy.test(manager.getId()), null, IfRunning.SKIP, true);
            r.settled().get(5, TimeUnit.SECONDS);
            assertThat(fresh(c).getStatus()).isEqualTo(ChatStatus.CLOSED);

            incoming(n1, VEENU, "chat", "Rahul", "Veenu");
        }
        WaWebChat after = fresh(c);
        assertThat(after.getStatus()).isIn(ChatStatus.BOT, ChatStatus.CLOSED);
        // the answer reopened it for the chatbot (or the chatbot already finished and closed it): never routed
        assertThat(routed(c)).hasSize(routedBefore);
        assertThat(after.getAssignedUserId()).isNull();
    }

    @Test
    void anAssignChatRuleActionStillAssigns() {
        rule("Assign", AutomationEventKind.MESSAGE_RECEIVED, RuleStatus.PUBLISHED, 20, null,
                new AutomationAction(AutomationActionType.ASSIGN_CHAT, null, null, AssignMode.AGENT, agent2.getId(), null,
                        null, null, null, null, null, null));
        chatbotRule(publishedChatbot(), "price");
        WaWebChat c = fresh(incoming(n1, VEENU, "chat", "hello", "Veenu"));

        worker.tick();

        assertThat(fresh(c).getAssignedUserId()).isEqualTo(agent2.getId());
        assertThat(routed(c)).hasSize(1);
    }

    @Test
    void aPlanWithoutChatbotsRoutesAtOnce() {
        chatbotRule(publishedChatbot(), null);
        billing.assign(acme.getId(), Set.of("automations"), Map.of());

        WaWebChat c = fresh(incoming(n1, VEENU, "chat", "hello", "Veenu"));

        assertThat(c.getAssignedUserId()).isNotNull();
        assertThat(c.getRoutingPendingSince()).isNull();
    }

    /** A chat waiting for its rules, as the ingestion leaves it. */
    private WaWebChat waiting() {
        WaWebChat c = new WaWebChat();
        c.setCustomerId(acme.getId());
        c.setWorkspaceId(brand.getId());
        c.setPhoneNumberId(n1.getId());
        c.setRemoteId(UUID.randomUUID() + "@c.us");
        c.setStatus(ChatStatus.OPEN);
        c.setRoutingPendingSince(clock.instant());
        return chats.save(c);
    }

    @Test
    void settleTwiceRoutesOnce() throws Exception {
        WaWebChat c = waiting();
        CountDownLatch go = new CountDownLatch(1);
        List<CompletableFuture<Boolean>> both = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            both.add(CompletableFuture.supplyAsync(() -> {
                try {
                    go.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return gate.settle(c.getId());
            }));
        }
        go.countDown();
        for (CompletableFuture<Boolean> f : both) f.get(5, TimeUnit.SECONDS);

        assertThat(routed(c)).hasSize(1);
        assertThat(fresh(c).getAssignedUserId()).isNotNull();
    }

    @Test
    void settleRacingATakeNeverLeavesABotChatWithAnAgent() throws Exception {
        for (int i = 0; i < 50; i++) {
            WaWebChat c = waiting();
            CountDownLatch go = new CountDownLatch(1);
            CompletableFuture<?> settle = CompletableFuture.runAsync(() -> {
                await(go);
                gate.settle(c.getId());
            });
            CompletableFuture<?> take = CompletableFuture.runAsync(() -> {
                await(go);
                botChats.take(c.getId());
            });
            go.countDown();
            settle.get(5, TimeUnit.SECONDS);
            take.get(5, TimeUnit.SECONDS);

            WaWebChat after = fresh(c);
            if (after.getStatus() == ChatStatus.BOT) {
                assertThat(after.getAssignedUserId()).as("chat %d", i).isNull();
            } else {
                assertThat(after.getStatus()).as("chat %d", i).isEqualTo(ChatStatus.OPEN);
                assertThat(after.getAssignedUserId()).as("chat %d", i).isNotNull();
            }
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
