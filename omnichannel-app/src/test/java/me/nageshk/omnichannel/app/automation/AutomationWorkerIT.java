package me.nageshk.omnichannel.app.automation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import me.nageshk.omnichannel.crm.deal.CrmDeal;
import me.nageshk.omnichannel.db.automation.AssignMode;
import me.nageshk.omnichannel.db.automation.AutomationAction;
import me.nageshk.omnichannel.db.automation.AutomationActionType;
import me.nageshk.omnichannel.db.automation.AutomationEvent;
import me.nageshk.omnichannel.db.automation.AutomationEventKind;
import me.nageshk.omnichannel.db.automation.AutomationEventRecord;
import me.nageshk.omnichannel.db.automation.AutomationRule;
import me.nageshk.omnichannel.db.automation.AutomationRuleRepository;
import me.nageshk.omnichannel.db.automation.AutomationRun;
import me.nageshk.omnichannel.db.automation.EventOutcome;
import me.nageshk.omnichannel.db.automation.EventStatus;
import me.nageshk.omnichannel.db.automation.RuleStatus;
import me.nageshk.omnichannel.db.automation.RunStatus;
import me.nageshk.omnichannel.db.automation.RunStep;
import me.nageshk.omnichannel.db.automation.StepOutcome;
import me.nageshk.omnichannel.db.automation.WhenAway;
import me.nageshk.omnichannel.db.inbox.MessageDirection;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import me.nageshk.omnichannel.db.inbox.WaWebMessage;
import me.nageshk.omnichannel.db.template.MessageTemplate;
import me.nageshk.omnichannel.db.template.TemplateStatus;
import me.nageshk.omnichannel.service.automation.AssignChatHandler;
import me.nageshk.omnichannel.service.automation.AutomationWorker;
import me.nageshk.omnichannel.service.rpc.Envelope;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** The worker claims queued events, runs every matching published rule in order once, and files the event away. */
class AutomationWorkerIT extends AutomationTestBase {

    private static final String ASHA = "919811100001@c.us";
    private static final String RAVI = "919811100002@c.us";
    private static final String OFFLINE = "The number's extension is offline or the number is disabled";
    private static final String INTERRUPTED = "The app stopped while sending; it may have gone";

    @Autowired AutomationWorker worker;
    @MockitoSpyBean AutomationRuleRepository ruleRepository;
    @MockitoSpyBean AssignChatHandler assignHandler;

    // ---- helpers ----

    private AutomationDevice device() throws Exception {
        return new AutomationDevice(port, testDevices, n1);
    }

    private static AutomationAction send(String templateId) {
        return new AutomationAction(AutomationActionType.SEND_TEMPLATE, templateId, null, null, null, null, null,
                null, null, null, null, null);
    }

    private static AutomationAction assign(AssignMode mode, String userId, String teamId) {
        return new AutomationAction(AutomationActionType.ASSIGN_CHAT, null, null, mode, userId, teamId,
                WhenAway.KEEP_WITH_AGENT, null, null, null, null, null);
    }

    private static AutomationAction addToCrm() {
        return new AutomationAction(AutomationActionType.ADD_TO_CRM, null, null, null, null, null, null, null, null,
                null, null, null);
    }

    /** CRM on, with the brand's default pipeline made (the first CRM request of a brand makes it). */
    private void crmOn() {
        enableCrm();
        call(HttpMethod.GET, "/api/workspaces/" + brand.getId() + "/crm/pipelines", manager, null);
    }

    /** The welcome rule of spec §1: a new chat gets the welcome, goes round robin in support, and lands in the CRM. */
    private AutomationRule welcome() {
        String t = template("Welcome", "Hi {{contact.name}}, welcome to Acme!");
        return rule("Welcome", AutomationEventKind.MESSAGE_RECEIVED, RuleStatus.PUBLISHED, null,
                all(leaf("chat.new", "is", true)), send(t), assign(AssignMode.TEAM_ROUND_ROBIN, null, support.getId()),
                addToCrm());
    }

    private List<String> sent(AutomationDevice d) {
        return d.requests("sendMessage").stream().map(r -> r.params().get("content").asString()).toList();
    }

    private List<AutomationRun> runs() {
        return mongo.findAll(AutomationRun.class);
    }

    private List<AutomationRun> runsOf(AutomationRule r) {
        return mongo.find(new Query(Criteria.where("ruleId").is(r.getId())), AutomationRun.class);
    }

    private AutomationRun onlyRunOf(AutomationRule r) {
        List<AutomationRun> found = runsOf(r);
        assertThat(found).hasSize(1);
        return found.get(0);
    }

    private static List<StepOutcome> outcomes(AutomationRun run) {
        return run.getSteps().stream().map(RunStep::outcome).toList();
    }

    private List<AutomationEventRecord> history() {
        return mongo.findAll(AutomationEventRecord.class);
    }

    private AutomationEvent onlyQueued() {
        assertThat(queued()).hasSize(1);
        return queued().get(0);
    }

    private WaWebChat stored(WaWebChat chat) {
        return chats.findById(chat.getId()).orElseThrow();
    }

    private List<CrmDeal> deals() {
        return mongo.findAll(CrmDeal.class);
    }

    // ---- tests ----

    @Test
    void welcomeRuleEndToEnd() throws Exception {
        crmOn();
        AutomationRule w = welcome();
        try (AutomationDevice d = device()) {
            WaWebChat chat = incoming(n1, ASHA, "chat", "Hello", "Asha");
            String eventId = onlyQueued().getId();

            assertThat(worker.tick()).isEqualTo(1);

            assertThat(sent(d)).containsExactly("Hi Asha, welcome to Acme!");
            WaWebChat after = stored(chat);
            assertThat(after.getAssignedUserId()).isIn(agent.getId(), agent2.getId());
            assertThat(after.getAssignedTeamId()).isEqualTo(support.getId());
            assertThat(deals()).singleElement()
                    .satisfies(deal -> assertThat(deal.getOwnerId()).isEqualTo(after.getAssignedUserId()));

            AutomationRun run = onlyRunOf(w);
            assertThat(runs()).hasSize(1);
            assertThat(run.getStatus()).isEqualTo(RunStatus.DONE);
            assertThat(outcomes(run)).containsExactly(StepOutcome.DONE, StepOutcome.DONE, StepOutcome.DONE);
            assertThat(run.getSteps()).extracting(RunStep::index).containsExactly(0, 1, 2);
            assertThat(run.getSteps()).extracting(RunStep::type).containsExactly(AutomationActionType.SEND_TEMPLATE,
                    AutomationActionType.ASSIGN_CHAT, AutomationActionType.ADD_TO_CRM);
            assertThat(run.getSteps().get(0).reason()).isEqualTo("Sent “Welcome”");
            assertThat(run.getEventId()).isEqualTo(eventId);
            assertThat(run.getEventKind()).isEqualTo(AutomationEventKind.MESSAGE_RECEIVED);
            assertThat(run.getRuleName()).isEqualTo("Welcome");
            assertThat(run.isTest()).isFalse();
            assertThat(run.getContactId()).isEqualTo(chat.getContactId());
            assertThat(run.getContactName()).isEqualTo("Asha");
            assertThat(run.getContactPhone()).isEqualTo("919811100001");
            assertThat(run.getSummary()).isEqualTo("Message from Asha: Hello");
            assertThat(run.getStartedAt()).isEqualTo(NOW);
            assertThat(run.getFinishedAt()).isEqualTo(NOW);

            assertThat(queued()).isEmpty();
            assertThat(history()).singleElement().satisfies(h -> {
                assertThat(h.getId()).isEqualTo(eventId);
                assertThat(h.getOutcome()).isEqualTo(EventOutcome.DONE);
                assertThat(h.getRuleIds()).containsExactly(w.getId());
                assertThat(h.getAttempts()).isEqualTo(1);
                assertThat(h.getError()).isNull();
                assertThat(h.getKind()).isEqualTo(AutomationEventKind.MESSAGE_RECEIVED);
                assertThat(h.getWorkspaceId()).isEqualTo(brand.getId());
                assertThat(h.getContactId()).isEqualTo(chat.getContactId());
                assertThat(h.getData().message().text()).isEqualTo("Hello");
                assertThat(h.getCreatedAt()).isEqualTo(NOW);
                assertThat(h.getFinishedAt()).isEqualTo(NOW);
            });

            assertThat(worker.tick()).isZero();
        }
    }

    @Test
    void existingChatDoesNotMatch() throws Exception {
        AutomationRule w = welcome();
        try (AutomationDevice d = device()) {
            incoming(n1, ASHA, "chat", "Hello", "Asha");
            worker.tick();
            incoming(n1, ASHA, "chat", "Are you there?", "Asha");

            assertThat(worker.tick()).isEqualTo(1);

            assertThat(runsOf(w)).hasSize(1);
            assertThat(sent(d)).hasSize(1);
            assertThat(history()).hasSize(2).filteredOn(h -> h.getRuleIds().isEmpty()).hasSize(1);
        }
    }

    @Test
    void movedToDraftStopsAtOnce() throws Exception {
        AutomationRule w = welcome();
        try (AutomationDevice d = device()) {
            incoming(n1, ASHA, "chat", "Hello", "Asha");
            assertThat(queued()).hasSize(1);
            mongo.updateFirst(new Query(Criteria.where("_id").is(w.getId())),
                    new Update().set("status", RuleStatus.DRAFT), AutomationRule.class);

            assertThat(worker.tick()).isEqualTo(1);

            assertThat(runs()).isEmpty();
            assertThat(sent(d)).isEmpty();
            assertThat(history()).singleElement().satisfies(h -> {
                assertThat(h.getOutcome()).isEqualTo(EventOutcome.DONE);
                assertThat(h.getRuleIds()).isEmpty();
            });
        }
    }

    @Test
    void draftRuleNeverRunsOnLiveEvent() throws Exception {
        String t = template("Welcome", "Hi {{contact.name}}");
        AutomationRule draft = rule("Draft", AutomationEventKind.MESSAGE_RECEIVED, RuleStatus.DRAFT, 100, null,
                send(t));
        rule("Prices", AutomationEventKind.MESSAGE_RECEIVED, RuleStatus.PUBLISHED, null,
                all(leaf("message.text", "contains", "price")), send(t));
        try (AutomationDevice d = device()) {
            incoming(n1, ASHA, "chat", "Hello", "Asha");

            assertThat(worker.tick()).isEqualTo(1);

            assertThat(runsOf(draft)).isEmpty();
            assertThat(runs()).isEmpty();
            assertThat(sent(d)).isEmpty();
            assertThat(history()).singleElement().satisfies(h -> assertThat(h.getRuleIds()).isEmpty());
        }
    }

    @Test
    void rulesRunInPriorityThenCreationOrder() throws Exception {
        Integer[] priorities = {null, 5, 10, null, 5};
        String[] letters = {"A", "B", "C", "D", "E"};
        for (int i = 0; i < letters.length; i++) {
            AutomationRule r = rule(letters[i], AutomationEventKind.MESSAGE_RECEIVED, RuleStatus.PUBLISHED,
                    priorities[i], null, send(template(letters[i], letters[i])));
            mongo.updateFirst(new Query(Criteria.where("_id").is(r.getId())),
                    new Update().set("creationTime", NOW.minus(Duration.ofDays(1)).plusSeconds(i)),
                    AutomationRule.class);
        }
        try (AutomationDevice d = device()) {
            incoming(n1, ASHA, "chat", "Hello", "Asha");

            worker.tick();

            assertThat(sent(d)).containsExactly("C", "B", "E", "A", "D");
            assertThat(runs()).hasSize(5).allSatisfy(r -> assertThat(r.getStatus()).isEqualTo(RunStatus.DONE));
        }
    }

    @Test
    void everyMatchingRuleSends() throws Exception {
        AutomationRule one = rule("One", AutomationEventKind.MESSAGE_RECEIVED, RuleStatus.PUBLISHED, 2, null,
                send(template("First", "First reply")));
        AutomationRule two = rule("Two", AutomationEventKind.MESSAGE_RECEIVED, RuleStatus.PUBLISHED, 1,
                all(leaf("message.text", "contains", "hello")), send(template("Second", "Second reply")));
        try (AutomationDevice d = device()) {
            incoming(n1, ASHA, "chat", "Hello there", "Asha");

            worker.tick();

            assertThat(sent(d)).containsExactly("First reply", "Second reply");
            assertThat(onlyRunOf(one).getStatus()).isEqualTo(RunStatus.DONE);
            assertThat(onlyRunOf(two).getStatus()).isEqualTo(RunStatus.DONE);
            assertThat(history()).singleElement()
                    .satisfies(h -> assertThat(h.getRuleIds()).containsExactlyInAnyOrder(one.getId(), two.getId()));
        }
    }

    @Test
    void laterRuleSeesEarlierRulesWork() {
        crmOn();
        AutomationRule adds = rule("Adds", AutomationEventKind.MESSAGE_RECEIVED, RuleStatus.PUBLISHED, 10, null,
                addToCrm());
        AutomationRule leads = rule("Leads", AutomationEventKind.MESSAGE_RECEIVED, RuleStatus.PUBLISHED, 5,
                all(leaf("contact.inCrm", "is", true)), assign(AssignMode.TEAM_QUEUE, null, support.getId()));

        incoming(n1, ASHA, "chat", "Hello", "Asha");
        assertThat(onlyQueued().getData().contact().inCrm()).isFalse();
        worker.tick();

        assertThat(onlyRunOf(adds).getStatus()).isEqualTo(RunStatus.DONE);
        assertThat(onlyRunOf(leads).getStatus()).isEqualTo(RunStatus.DONE);

        // swapped: the condition is read before the CRM rule has run
        mongo.updateFirst(new Query(Criteria.where("_id").is(leads.getId())), new Update().set("priority", 20),
                AutomationRule.class);
        incoming(n1, RAVI, "chat", "Hello", "Ravi");
        String second = onlyQueued().getId();
        worker.tick();

        assertThat(mongo.find(new Query(Criteria.where("eventId").is(second)), AutomationRun.class))
                .extracting(AutomationRun::getRuleId).containsExactly(adds.getId());
        assertThat(deals()).hasSize(2);
    }

    @Test
    void brokenActionIsLoggedAndNextActionRuns() {
        String t = template("Welcome", "Hi {{contact.name}}");
        mongo.updateFirst(new Query(Criteria.where("_id").is(t)), new Update().set("status", TemplateStatus.ARCHIVED),
                MessageTemplate.class);
        AutomationRule r = rule("Broken", AutomationEventKind.MESSAGE_RECEIVED, RuleStatus.PUBLISHED, null, null,
                send(t), assign(AssignMode.AGENT, "no-such-user", null),
                assign(AssignMode.TEAM_QUEUE, null, support.getId()));
        WaWebChat chat = incoming(n1, ASHA, "chat", "Hello", "Asha");

        worker.tick();

        AutomationRun run = onlyRunOf(r);
        assertThat(outcomes(run)).containsExactly(StepOutcome.FAILED, StepOutcome.FAILED, StepOutcome.DONE);
        assertThat(run.getSteps()).extracting(RunStep::reason).containsExactly("The template is archived",
                "The agent is no longer in the brand", "Waiting in support's queue");
        assertThat(run.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(run.getFinishedAt()).isEqualTo(NOW);
        WaWebChat after = stored(chat);
        assertThat(after.getAssignedTeamId()).isEqualTo(support.getId());
        assertThat(after.getAssignedUserId()).isNull();
        // a failed action is the rule's business: the event itself is done
        assertThat(history()).singleElement().satisfies(h -> assertThat(h.getOutcome()).isEqualTo(EventOutcome.DONE));
    }

    @Test
    void unexpectedHandlerErrorFailsItsStepOnly() throws Exception {
        doThrow(new OptimisticLockingFailureException("the chat changed")).when(assignHandler)
                .run(ArgumentMatchers.any(), ArgumentMatchers.any());
        AutomationRule r = rule("Assign then send", AutomationEventKind.MESSAGE_RECEIVED, RuleStatus.PUBLISHED, null,
                null, assign(AssignMode.TEAM_QUEUE, null, support.getId()), send(template("Hi", "Hi there")));
        try (AutomationDevice d = device()) {
            incoming(n1, ASHA, "chat", "Hello", "Asha");

            assertThat(worker.tick()).isEqualTo(1);

            AutomationRun run = onlyRunOf(r);
            assertThat(outcomes(run)).containsExactly(StepOutcome.FAILED, StepOutcome.DONE);
            assertThat(run.getSteps().get(0).reason())
                    .isEqualTo("Unexpected error: OptimisticLockingFailureException");
            assertThat(run.getStatus()).isEqualTo(RunStatus.FAILED);
            assertThat(sent(d)).containsExactly("Hi there");
            assertThat(history()).singleElement()
                    .satisfies(h -> assertThat(h.getOutcome()).isEqualTo(EventOutcome.DONE));
        }
    }

    @Test
    void eventClaimedTwiceRunsEachRuleOnce() throws Exception {
        crmOn();
        AutomationRule w = welcome();
        try (AutomationDevice d = device()) {
            incoming(n1, ASHA, "chat", "Hello", "Asha");
            AutomationEvent copy = onlyQueued();

            worker.tick();
            assertThat(queued()).isEmpty();
            mongo.insert(copy);

            assertThat(worker.tick()).isEqualTo(1);

            assertThat(sent(d)).hasSize(1);
            assertThat(runsOf(w)).hasSize(1);
            assertThat(onlyRunOf(w).getStatus()).isEqualTo(RunStatus.DONE);
            assertThat(queued()).isEmpty();
            assertThat(history()).singleElement().satisfies(h -> assertThat(h.getAttempts()).isEqualTo(1));
        }
    }

    @Test
    void expiredLeaseIsClaimedAgain() throws Exception {
        crmOn();
        AutomationRule w = welcome();
        try (AutomationDevice d = device()) {
            WaWebChat chat = incoming(n1, ASHA, "chat", "Hello", "Asha");
            AutomationEvent e = onlyQueued();
            // the app died while sending: the event is leased and the run has no step yet
            mongo.updateFirst(new Query(Criteria.where("_id").is(e.getId())), new Update()
                    .set("status", EventStatus.RUNNING).set("attempts", 1)
                    .set("leaseUntil", NOW.plus(Duration.ofMinutes(1))), AutomationEvent.class);
            AutomationRun stale = new AutomationRun();
            stale.setCustomerId(acme.getId());
            stale.setWorkspaceId(brand.getId());
            stale.setEventId(e.getId());
            stale.setEventKind(AutomationEventKind.MESSAGE_RECEIVED);
            stale.setRuleId(w.getId());
            stale.setRuleName(w.getName());
            stale.setStatus(RunStatus.RUNNING);
            stale.setSteps(new ArrayList<>());
            stale.setStartedAt(NOW.minus(Duration.ofMinutes(6)));
            mongo.insert(stale);

            assertThat(worker.tick()).as("a lease still running is left alone").isZero();
            mongo.updateFirst(new Query(Criteria.where("_id").is(e.getId())),
                    new Update().set("leaseUntil", NOW.minusSeconds(1)), AutomationEvent.class);

            assertThat(worker.tick()).isEqualTo(1);

            AutomationRun run = onlyRunOf(w);
            assertThat(outcomes(run)).containsExactly(StepOutcome.INTERRUPTED, StepOutcome.DONE, StepOutcome.DONE);
            assertThat(run.getSteps().get(0).reason()).isEqualTo(INTERRUPTED);
            assertThat(run.getStatus()).isEqualTo(RunStatus.INTERRUPTED);
            assertThat(run.getFinishedAt()).isEqualTo(NOW);
            assertThat(sent(d)).isEmpty();
            WaWebChat after = stored(chat);
            assertThat(after.getAssignedUserId()).isIn(agent.getId(), agent2.getId());
            assertThat(deals()).hasSize(1);
            assertThat(queued()).isEmpty();
            assertThat(history()).singleElement().satisfies(h -> {
                assertThat(h.getOutcome()).isEqualTo(EventOutcome.DONE);
                assertThat(h.getAttempts()).isEqualTo(2);
                assertThat(h.getRuleIds()).containsExactly(w.getId());
            });
        }
    }

    @Test
    void offlineExtensionIsRetried() throws Exception {
        crmOn();
        AutomationRule w = welcome();
        incoming(n1, ASHA, "chat", "Hello", "Asha");

        assertThat(worker.tick()).isEqualTo(1);

        AutomationRun run = onlyRunOf(w);
        assertThat(run.getStatus()).isEqualTo(RunStatus.RUNNING);
        assertThat(outcomes(run)).containsExactly(StepOutcome.RETRYING);
        assertThat(run.getSteps().get(0).reason()).isEqualTo(OFFLINE);
        AutomationEvent e = onlyQueued();
        assertThat(e.getStatus()).isEqualTo(EventStatus.PENDING);
        assertThat(e.getAttempts()).isEqualTo(1);
        assertThat(e.getNotBefore()).isEqualTo(NOW.plus(Duration.ofMinutes(2)));
        assertThat(e.getLastError()).isEqualTo(OFFLINE);
        assertThat(deals()).isEmpty();
        assertThat(history()).isEmpty();

        assertThat(worker.tick()).as("not before the retry delay").isZero();

        clock.advance(Duration.ofMinutes(2));
        try (AutomationDevice d = device()) {
            assertThat(worker.tick()).isEqualTo(1);

            assertThat(sent(d)).containsExactly("Hi Asha, welcome to Acme!");
            AutomationRun after = onlyRunOf(w);
            assertThat(outcomes(after)).containsExactly(StepOutcome.DONE, StepOutcome.DONE, StepOutcome.DONE);
            assertThat(after.getStatus()).isEqualTo(RunStatus.DONE);
            assertThat(deals()).hasSize(1);
            assertThat(queued()).isEmpty();
            assertThat(history()).singleElement().satisfies(h -> {
                assertThat(h.getOutcome()).isEqualTo(EventOutcome.DONE);
                assertThat(h.getAttempts()).isEqualTo(2);
            });
        }
    }

    @Test
    void retriedSendIsClearedBeforeItIsSent() throws Exception {
        String t = template("Welcome", "Hi {{contact.name}}");
        AutomationRule r = rule("Welcome", AutomationEventKind.MESSAGE_RECEIVED, RuleStatus.PUBLISHED, null, null,
                send(t));
        incoming(n1, ASHA, "chat", "Hello", "Asha");
        worker.tick();
        assertThat(outcomes(onlyRunOf(r))).containsExactly(StepOutcome.RETRYING);

        clock.advance(Duration.ofMinutes(2));
        AtomicBoolean asked = new AtomicBoolean();
        AtomicReference<RunStep> duringSend = new AtomicReference<>();
        try (AutomationDevice d = device()) {
            d.sendAnswer = (Envelope req) -> {
                List<RunStep> steps = onlyRunOf(r).getSteps();
                duringSend.set(steps == null || steps.isEmpty() ? null : steps.get(0));
                asked.set(true);
                return Envelope.response(req.id(), AutomationDevice.model(req, 1));
            };
            assertThat(worker.tick()).isEqualTo(1);

            assertThat(asked).isTrue();
            assertThat(duringSend.get()).as("step 0 while the resend is in flight").isNull();
            AutomationRun after = onlyRunOf(r);
            assertThat(after.getStatus()).isEqualTo(RunStatus.DONE);
            assertThat(outcomes(after)).containsExactly(StepOutcome.DONE);
            assertThat(d.requests("sendMessage")).hasSize(1);
        }
    }

    @Test
    void offlineExtensionGivesUpAfterThreeAttempts() {
        crmOn();
        AutomationRule w = welcome();
        incoming(n1, ASHA, "chat", "Hello", "Asha");

        worker.tick();
        clock.advance(Duration.ofMinutes(2));
        worker.tick();
        assertThat(onlyQueued().getAttempts()).isEqualTo(2);
        assertThat(outcomes(onlyRunOf(w))).containsExactly(StepOutcome.RETRYING);
        clock.advance(Duration.ofMinutes(2));
        worker.tick();

        AutomationRun run = onlyRunOf(w);
        assertThat(outcomes(run)).containsExactly(StepOutcome.FAILED, StepOutcome.DONE, StepOutcome.DONE);
        assertThat(run.getSteps().get(0).reason()).isEqualTo(OFFLINE);
        assertThat(run.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(deals()).hasSize(1);
        assertThat(queued()).isEmpty();
        assertThat(history()).singleElement().satisfies(h -> {
            assertThat(h.getOutcome()).isEqualTo(EventOutcome.DONE);
            assertThat(h.getAttempts()).isEqualTo(3);
        });
    }

    @Test
    void interruptedSendIsNotResentAndTheRuleGoesOn() throws Exception {
        crmOn();
        AutomationRule w = welcome();
        try (AutomationDevice d = device()) {
            d.sendAnswer = req -> null;
            WaWebChat chat = incoming(n1, ASHA, "chat", "Hello", "Asha");
            AutomationEvent copy = onlyQueued();

            assertThat(worker.tick()).isEqualTo(1);

            AutomationRun run = onlyRunOf(w);
            assertThat(outcomes(run)).containsExactly(StepOutcome.INTERRUPTED, StepOutcome.DONE, StepOutcome.DONE);
            assertThat(run.getSteps().get(0).reason())
                    .isEqualTo("The extension did not answer in time; the message may have gone");
            assertThat(run.getStatus()).isEqualTo(RunStatus.INTERRUPTED);
            assertThat(stored(chat).getAssignedUserId()).isIn(agent.getId(), agent2.getId());
            assertThat(deals()).hasSize(1);
            assertThat(queued()).isEmpty();
            assertThat(history()).singleElement().satisfies(h -> {
                assertThat(h.getOutcome()).isEqualTo(EventOutcome.DONE);
                assertThat(h.getAttempts()).isEqualTo(1);
            });

            clock.advance(Duration.ofMinutes(6));
            assertThat(worker.tick()).isZero();
            // the same event claimed once more finds the run settled
            mongo.insert(copy);
            assertThat(worker.tick()).isEqualTo(1);

            assertThat(d.requests("sendMessage")).hasSize(1);
            assertThat(runsOf(w)).hasSize(1);
            assertThat(onlyRunOf(w).getStatus()).isEqualTo(RunStatus.INTERRUPTED);
        }
    }

    @Test
    void laterRulesWaitForTheRetry() throws Exception {
        AutomationRule a = rule("A", AutomationEventKind.MESSAGE_RECEIVED, RuleStatus.PUBLISHED, 10, null,
                send(template("Hello", "Hello from A")));
        AutomationRule b = rule("B", AutomationEventKind.MESSAGE_RECEIVED, RuleStatus.PUBLISHED, 5, null,
                assign(AssignMode.TEAM_QUEUE, null, support.getId()));
        WaWebChat chat = incoming(n1, ASHA, "chat", "Hello", "Asha");

        worker.tick();

        assertThat(outcomes(onlyRunOf(a))).containsExactly(StepOutcome.RETRYING);
        assertThat(runsOf(b)).isEmpty();

        clock.advance(Duration.ofMinutes(2));
        try (AutomationDevice d = device()) {
            worker.tick();

            assertThat(sent(d)).containsExactly("Hello from A");
            AutomationRun runA = onlyRunOf(a);
            AutomationRun runB = onlyRunOf(b);
            assertThat(runA.getStatus()).isEqualTo(RunStatus.DONE);
            assertThat(runB.getStatus()).isEqualTo(RunStatus.DONE);
            assertThat(runA.getStartedAt()).isEqualTo(NOW);
            assertThat(runB.getStartedAt()).isEqualTo(NOW.plus(Duration.ofMinutes(2)));
            assertThat(runB.getStartedAt()).isAfterOrEqualTo(runA.getSteps().get(0).at());
            assertThat(stored(chat).getAssignedTeamId()).isEqualTo(support.getId());
            assertThat(history()).singleElement()
                    .satisfies(h -> assertThat(h.getRuleIds()).containsExactlyInAnyOrder(a.getId(), b.getId()));
        }
    }

    @Test
    void ruleMovedToDraftDuringRetryEndsItsRun() throws Exception {
        AutomationRule w = welcome();
        incoming(n1, ASHA, "chat", "Hello", "Asha");
        worker.tick();
        assertThat(onlyRunOf(w).getStatus()).isEqualTo(RunStatus.RUNNING);
        mongo.updateFirst(new Query(Criteria.where("_id").is(w.getId())),
                new Update().set("status", RuleStatus.DRAFT), AutomationRule.class);

        clock.advance(Duration.ofMinutes(2));
        try (AutomationDevice d = device()) {
            assertThat(worker.tick()).isEqualTo(1);

            assertThat(sent(d)).isEmpty();
            AutomationRun run = onlyRunOf(w);
            assertThat(run.getStatus()).isEqualTo(RunStatus.INTERRUPTED);
            assertThat(run.getFinishedAt()).isEqualTo(NOW.plus(Duration.ofMinutes(2)));
            assertThat(queued()).isEmpty();
            assertThat(history()).singleElement().satisfies(h -> {
                assertThat(h.getOutcome()).isEqualTo(EventOutcome.DONE);
                assertThat(h.getRuleIds()).containsExactly(w.getId());
            });
        }
    }

    @Test
    void failingEventRetriesThenFails() {
        welcome();
        incoming(n1, ASHA, "chat", "Hello", "Asha");
        doThrow(new IllegalStateException("rules unreadable")).when(ruleRepository)
                .findByWorkspaceIdAndEventAndStatus(ArgumentMatchers.any(), ArgumentMatchers.any(),
                        ArgumentMatchers.any());

        assertThat(worker.tick()).isEqualTo(1);
        assertThat(onlyQueued().getStatus()).isEqualTo(EventStatus.PENDING);
        assertThat(onlyQueued().getAttempts()).isEqualTo(1);
        assertThat(onlyQueued().getLastError()).isEqualTo("rules unreadable");

        worker.tick();
        assertThat(onlyQueued().getStatus()).isEqualTo(EventStatus.PENDING);
        assertThat(onlyQueued().getAttempts()).isEqualTo(2);

        worker.tick();
        assertThat(queued()).isEmpty();
        assertThat(runs()).isEmpty();
        assertThat(history()).singleElement().satisfies(h -> {
            assertThat(h.getOutcome()).isEqualTo(EventOutcome.FAILED);
            assertThat(h.getError()).isEqualTo("rules unreadable");
            assertThat(h.getAttempts()).isEqualTo(3);
        });
    }

    @Test
    void automationSendStartsNoEvent() throws Exception {
        welcome();
        try (AutomationDevice d = device()) {
            WaWebChat chat = incoming(n1, ASHA, "chat", "Hello", "Asha");
            worker.tick();
            assertThat(sent(d)).hasSize(1);
            WaWebMessage out = mongo.findOne(new Query(Criteria.where("chatId").is(chat.getId())
                    .and("direction").is(MessageDirection.OUT)), WaWebMessage.class);

            // the extension reports our own send back, as it does for every message
            ObjectNode echo = payload(ASHA, true, "chat", sent(d).get(0), null);
            ((ObjectNode) echo.get("id")).put("_serialized", out.getWaMessageId());
            publish(n1, echo);

            assertThat(queued()).isEmpty();
            assertThat(worker.tick()).isZero();
            assertThat(sent(d)).hasSize(1);
            assertThat(history()).hasSize(1);
        }
    }

    @Test
    void laneChangeSendsThankYou() throws Exception {
        crmOn();
        rule("Leads", AutomationEventKind.MESSAGE_RECEIVED, RuleStatus.PUBLISHED, null, null, addToCrm());
        AutomationRule thanks = rule("Thanks", AutomationEventKind.DEAL_LANE_CHANGED, RuleStatus.PUBLISHED, null,
                all(leaf("toLane.type", "is", "WON")), send(template("Thanks", "Thank you for your order!")));
        try (AutomationDevice d = device()) {
            WaWebChat chat = incoming(n1, ASHA, "chat", "Hello", "Asha");
            worker.tick();
            CrmDeal deal = deals().get(0);
            assertThat(sent(d)).isEmpty();

            ResponseEntity<JsonNode> closed = call(HttpMethod.POST,
                    "/api/workspaces/" + brand.getId() + "/crm/deals/" + deal.getId() + "/close", manager,
                    Map.of("outcome", "WON"));
            assertThat(closed.getStatusCode()).isEqualTo(HttpStatus.OK);
            AutomationEvent e = onlyQueued();
            assertThat(e.getKind()).isEqualTo(AutomationEventKind.DEAL_LANE_CHANGED);
            String from = e.getData().fromLane().name();

            assertThat(worker.tick()).isEqualTo(1);

            assertThat(sent(d)).containsExactly("Thank you for your order!");
            assertThat(d.requests("sendMessage").get(0).params().get("chatId").asString()).isEqualTo(ASHA);
            AutomationRun run = onlyRunOf(thanks);
            assertThat(run.getStatus()).isEqualTo(RunStatus.DONE);
            assertThat(run.getEventKind()).isEqualTo(AutomationEventKind.DEAL_LANE_CHANGED);
            assertThat(run.getDealId()).isEqualTo(deal.getId());
            assertThat(run.getDealTitle()).isEqualTo("Asha – WhatsApp");
            assertThat(run.getContactId()).isEqualTo(chat.getContactId());
            assertThat(run.getSummary()).isEqualTo("Asha – WhatsApp moved from " + from + " to Won");
            assertThat(history()).filteredOn(h -> h.getKind() == AutomationEventKind.DEAL_LANE_CHANGED)
                    .singleElement().satisfies(h -> {
                        assertThat(h.getDealId()).isEqualTo(deal.getId());
                        assertThat(h.getRuleIds()).containsExactly(thanks.getId());
                    });
        }
    }
}
