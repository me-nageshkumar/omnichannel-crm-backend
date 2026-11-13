package me.nageshk.omnichannel.app.automation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import me.nageshk.omnichannel.db.automation.AssignMode;
import me.nageshk.omnichannel.db.automation.AutomationAction;
import me.nageshk.omnichannel.db.automation.AutomationActionType;
import me.nageshk.omnichannel.db.automation.AutomationEventKind;
import me.nageshk.omnichannel.db.automation.AutomationEventRecord;
import me.nageshk.omnichannel.db.automation.AutomationRule;
import me.nageshk.omnichannel.db.automation.AutomationRun;
import me.nageshk.omnichannel.db.automation.AutomationTest;
import me.nageshk.omnichannel.db.automation.EventOutcome;
import me.nageshk.omnichannel.db.automation.RuleStatus;
import me.nageshk.omnichannel.db.automation.RunStatus;
import me.nageshk.omnichannel.db.automation.RunStep;
import me.nageshk.omnichannel.db.automation.StepOutcome;
import me.nageshk.omnichannel.db.automation.TestEndReason;
import me.nageshk.omnichannel.db.automation.TestStatus;
import me.nageshk.omnichannel.db.automation.WhenAway;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import me.nageshk.omnichannel.db.template.MessageTemplate;
import me.nageshk.omnichannel.db.template.TemplateStatus;
import me.nageshk.omnichannel.service.automation.AutomationWorker;
import me.nageshk.omnichannel.service.contact.Phones;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/** A manager tests a draft rule on one contact, a condition and an action at a time, without touching live traffic. */
class AutomationTestsApiIT extends AutomationTestBase {

    private static final String ASHA = "919811100001@c.us";
    private static final String ASHA_PHONE = "919811100001";
    private static final String RAVI = "919811100002@c.us";
    private static final String TYPED = "919811100009";
    private static final String WELCOME_TEXT = "Hi Asha, welcome to Acme!";
    private static final String MESSAGED = "This number has already messaged you. Choose it as an existing contact "
            + "and switch on “Treat this chat as new”.";

    @Autowired AutomationWorker worker;

    private String welcome;
    private AutomationRule draft;

    @BeforeEach
    void draftRule() {
        welcome = template("Welcome", "Hi {{contact.name}}, welcome to Acme!");
        draft = rule("Welcome draft", AutomationEventKind.MESSAGE_RECEIVED, RuleStatus.DRAFT, null,
                all(leaf("chat.new", "is", true)), send(welcome), addToCrm());
    }

    // ---- helpers ----

    private static AutomationAction send(String templateId) {
        return new AutomationAction(AutomationActionType.SEND_TEMPLATE, templateId, null, null, null, null, null,
                null, null, null, null, null);
    }

    private static AutomationAction addToCrm() {
        return new AutomationAction(AutomationActionType.ADD_TO_CRM, null, null, null, null, null, null, null, null,
                null, null, null);
    }

    private static AutomationAction queueIn(String teamId) {
        return new AutomationAction(AutomationActionType.ASSIGN_CHAT, null, null, AssignMode.TEAM_QUEUE, null, teamId,
                WhenAway.KEEP_WITH_AGENT, null, null, null, null, null);
    }

    private AutomationDevice device() throws Exception {
        return new AutomationDevice(port, testDevices, n1);
    }

    private List<String> sent(AutomationDevice d) {
        return d.requests("sendMessage").stream().map(r -> r.params().get("content").asString()).toList();
    }

    /** CRM on, with the brand's default pipeline made. */
    private void crmOn() {
        enableCrm();
        call(HttpMethod.GET, "/api/workspaces/" + brand.getId() + "/crm/pipelines", manager, null);
    }

    private static Map<String, Object> byPhone(String phone) {
        Map<String, Object> m = new HashMap<>();
        m.put("contactId", null);
        m.put("phone", phone);
        m.put("treatAsNew", false);
        return m;
    }

    private static Map<String, Object> byContact(String contactId, boolean treatAsNew) {
        Map<String, Object> m = new HashMap<>();
        m.put("contactId", contactId);
        m.put("phone", null);
        m.put("treatAsNew", treatAsNew);
        return m;
    }

    private ResponseEntity<JsonNode> start(AutomationRule r, Map<String, Object> body) {
        return api(HttpMethod.POST, "/rules/" + r.getId() + "/tests", manager, body);
    }

    private JsonNode started(Map<String, Object> body) {
        ResponseEntity<JsonNode> res = start(draft, body);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return res.getBody();
    }

    private ResponseEntity<JsonNode> post(String testId, String what) {
        return api(HttpMethod.POST, "/tests/" + testId + "/" + what, manager, null);
    }

    private JsonNode ok(ResponseEntity<JsonNode> res) {
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        return res.getBody();
    }

    private JsonNode get(String testId) {
        return ok(api(HttpMethod.GET, "/tests/" + testId, manager, null));
    }

    private static void assertState(ResponseEntity<JsonNode> res, String message) {
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(res.getBody().get("code").asString()).isEqualTo("AUTOMATION_TEST_STATE");
        assertThat(res.getBody().get("message").asString()).isEqualTo(message);
    }

    private static Instant time(JsonNode n) {
        return Instant.parse(n.asString());
    }

    private AutomationTest stored(String testId) {
        return mongo.findById(testId, AutomationTest.class);
    }

    private List<AutomationRun> runsOfTest(String testId) {
        return mongo.find(new Query(Criteria.where("testId").is(testId)), AutomationRun.class);
    }

    private List<AutomationRun> liveRuns() {
        return mongo.find(new Query(Criteria.where("test").is(false)), AutomationRun.class);
    }

    private static List<StepOutcome> outcomes(AutomationRun run) {
        return run.getSteps().stream().map(RunStep::outcome).toList();
    }

    /** A test on Asha's number that has captured her first message. */
    private String capturedTest() {
        String id = started(byPhone(ASHA_PHONE)).get("id").asString();
        incoming(n1, ASHA, "chat", "Hello", "Asha");
        worker.tick();
        assertThat(get(id).get("status").asString()).isEqualTo("CAPTURED");
        return id;
    }

    /** Checks the one condition, sends the welcome and skips Add to CRM. */
    private void stepThrough(String id, AutomationDevice d) {
        JsonNode checked = ok(post(id, "check"));
        JsonNode c = checked.get("conditions");
        assertThat(c).hasSize(1);
        assertThat(c.get(0).get("path").asString()).isEqualTo("rules[0]");
        assertThat(c.get(0).get("field").asString()).isEqualTo("chat.new");
        assertThat(c.get(0).get("operator").asString()).isEqualTo("is");
        assertThat(c.get(0).get("value").asBoolean()).isTrue();
        assertThat(c.get(0).get("actual").asBoolean()).isTrue();
        assertThat(c.get(0).get("passed").asBoolean()).isTrue();
        assertThat(checked.get("matched").asBoolean()).isTrue();

        assertState(post(id, "check"), "Every condition is checked");
        assertState(post(id, "actions/1/run"), "Run or skip action 1 first");

        JsonNode ran = ok(post(id, "actions/0/run"));
        assertThat(ran.get("actions").get(0).get("outcome").asString()).isEqualTo("DONE");
        assertThat(ran.get("actions").get(0).get("reason").asString()).isEqualTo("Sent “Welcome”");
        assertThat(time(ran.get("actions").get(0).get("at"))).isEqualTo(NOW);
        assertThat(ran.get("actions").get(1).get("outcome").isNull()).isTrue();
        assertThat(ran.get("nextAction").asInt()).isEqualTo(1);
        assertThat(sent(d)).contains(WELCOME_TEXT);

        assertState(post(id, "actions/0/skip"), "Run or skip action 2 first");
        JsonNode skipped = ok(post(id, "actions/1/skip"));
        assertThat(skipped.get("actions").get(1).get("outcome").asString()).isEqualTo("SKIPPED");
        assertThat(skipped.get("actions").get(1).get("reason").asString()).isEqualTo("Skipped in the test");
        assertThat(skipped.get("nextAction").isNull()).isTrue();
        assertState(post(id, "actions/1/run"), "Every action is done");
    }

    // ---- tests ----

    @Test
    void typedNumberWaitsForItsFirstMessage() {
        JsonNode t = started(byPhone("+91 98111 00009"));
        String id = t.get("id").asString();
        assertThat(t.get("status").asString()).isEqualTo("WAITING");
        assertThat(t.get("ruleId").asString()).isEqualTo(draft.getId());
        assertThat(t.get("ruleName").asString()).isEqualTo("Welcome draft");
        assertThat(t.get("event").asString()).isEqualTo("MESSAGE_RECEIVED");
        assertThat(t.get("endReason").isNull()).isTrue();
        assertThat(t.get("startedBy").get("id").asString()).isEqualTo(manager.getId());
        assertThat(t.get("startedBy").get("name").asString()).isEqualTo(manager.getName());
        assertThat(t.get("target").get("phone").asString()).isEqualTo(TYPED);
        assertThat(t.get("target").get("contactId").isNull()).isTrue();
        assertThat(t.get("target").get("name").isNull()).isTrue();
        assertThat(t.get("treatAsNew").asBoolean()).isFalse();
        List<String> numberIds = new ArrayList<>();
        t.get("numbers").forEach(n -> numberIds.add(n.get("id").asString()));
        assertThat(numberIds).containsExactlyInAnyOrder(n1.getId(), n2.getId());
        assertThat(t.get("numbers").get(0).has("number")).isTrue();
        assertThat(time(t.get("waitingSince"))).isEqualTo(NOW);
        assertThat(time(t.get("idleEndsAt"))).isEqualTo(NOW.plus(Duration.ofMinutes(30)));
        assertThat(t.get("captured").isNull()).isTrue();
        assertThat(t.get("conditionCount").asInt()).isEqualTo(1);
        assertThat(t.get("conditions")).isEmpty();
        assertThat(t.get("matched").isNull()).isTrue();
        assertThat(t.get("actions")).hasSize(2);
        assertThat(t.get("actions").get(0).get("index").asInt()).isZero();
        assertThat(t.get("actions").get(0).get("type").asString()).isEqualTo("SEND_TEMPLATE");
        assertThat(t.get("actions").get(0).get("summary").asString()).isEqualTo("Send “Welcome”");
        assertThat(t.get("actions").get(0).get("outcome").isNull()).isTrue();
        assertThat(t.get("actions").get(1).get("summary").asString()).isEqualTo("Add to CRM (default pipeline)");
        assertThat(t.get("nextAction").isNull()).isTrue();
        assertThat(t.get("runId").isNull()).isTrue();

        AutomationTest s = stored(id);
        assertThat(s.isActive()).isTrue();
        assertThat(s.getStatus()).isEqualTo(TestStatus.WAITING);
        assertThat(s.getTargetPhone()).isEqualTo(TYPED);
        assertThat(s.getWaitingSince()).isEqualTo(NOW);
        assertThat(s.getLastActivityAt()).isEqualTo(NOW);
        assertThat(s.getWorkspaceId()).isEqualTo(brand.getId());
        assertThat(s.getCustomerId()).isEqualTo(acme.getId());

        assertState(post(id, "check"), "Wait for the event first");
        assertState(post(id, "actions/0/run"), "Wait for the event first");
        assertState(post(id, "replay"), "Wait for the event first");

        incoming(n1, RAVI, "chat", "Hi", "Ravi");
        assertThat(queued()).isEmpty();
        worker.tick();
        assertThat(get(id).get("status").asString()).isEqualTo("WAITING");

        WaWebChat chat = incoming(n1, TYPED + "@c.us", "chat", "Hello", "Asha");
        assertThat(queued()).hasSize(1);
        String eventId = queued().get(0).getId();
        clock.advance(Duration.ofMinutes(1));
        worker.tick();

        JsonNode c = get(id);
        assertThat(c.get("status").asString()).isEqualTo("CAPTURED");
        assertThat(c.get("captured").get("eventId").asString()).isEqualTo(eventId);
        assertThat(time(c.get("captured").get("at"))).isEqualTo(NOW.plus(Duration.ofMinutes(1)));
        assertThat(c.get("captured").get("treatedAsNew").asBoolean()).isFalse();
        assertThat(c.get("captured").get("data").get("chat").get("isNew").asBoolean()).isTrue();
        assertThat(c.get("captured").get("data").get("chat").get("id").asString()).isEqualTo(chat.getId());
        assertThat(c.get("captured").get("data").get("message").get("text").asString()).isEqualTo("Hello");
        assertThat(c.get("conditionCount").asInt()).isEqualTo(1);
        assertThat(c.get("conditions")).isEmpty();
        assertThat(c.get("matched").isNull()).isTrue();
        assertThat(c.get("nextAction").asInt()).isZero();
        String runId = c.get("runId").asString();

        AutomationRun run = mongo.findById(runId, AutomationRun.class);
        assertThat(run.isTest()).isTrue();
        assertThat(run.getTestId()).isEqualTo(id);
        assertThat(run.getStatus()).isEqualTo(RunStatus.TESTING);
        assertThat(run.getSteps()).isEmpty();
        assertThat(run.getEventId()).isEqualTo(eventId);
        assertThat(run.getRuleId()).isEqualTo(draft.getId());
        assertThat(run.getRuleName()).isEqualTo("Welcome draft");
        assertThat(run.getContactPhone()).isEqualTo(TYPED);
        AutomationTest s2 = stored(id);
        assertThat(s2.getEventId()).isEqualTo(eventId);
        assertThat(s2.getRunId()).isEqualTo(runId);
        assertThat(s2.getConditionResults()).isEmpty();
        assertThat(s2.getNextAction()).isZero();
    }

    @Test
    void stepThroughConditionsThenActions() throws Exception {
        try (AutomationDevice d = device()) {
            String id = capturedTest();
            stepThrough(id, d);
            assertThat(sent(d)).containsExactly(WELCOME_TEXT);

            AutomationRun run = mongo.findById(stored(id).getRunId(), AutomationRun.class);
            assertThat(run.isTest()).isTrue();
            assertThat(run.getStatus()).isEqualTo(RunStatus.DONE);
            assertThat(run.getFinishedAt()).isEqualTo(NOW);
            assertThat(outcomes(run)).containsExactly(StepOutcome.DONE, StepOutcome.SKIPPED);
            assertThat(run.getSteps().get(1).reason()).isEqualTo("Skipped in the test");
            AutomationTest s = stored(id);
            assertThat(s.getMatched()).isTrue();
            assertThat(s.getNextAction()).isNull();
            assertThat(s.getConditionResults()).singleElement().satisfies(r -> assertThat(r.passed()).isTrue());
            assertThat(get(id).get("status").asString()).isEqualTo("CAPTURED");
        }
    }

    @Test
    void treatAsNewOverridesExistingChat() {
        WaWebChat chat = incoming(n1, ASHA, "chat", "Hello", "Asha");
        incoming(n1, ASHA, "chat", "Anyone?", "Asha");
        assertThat(queued()).isEmpty();

        JsonNode t = started(byContact(chat.getContactId(), true));
        String id = t.get("id").asString();
        assertThat(t.get("target").get("contactId").asString()).isEqualTo(chat.getContactId());
        assertThat(t.get("target").get("name").asString()).isEqualTo("Asha");
        assertThat(t.get("target").get("phone").asString()).isEqualTo(ASHA_PHONE);
        assertThat(t.get("treatAsNew").asBoolean()).isTrue();
        incoming(n1, ASHA, "chat", "Hello again", "Asha");
        worker.tick();
        JsonNode c = get(id);
        assertThat(c.get("captured").get("treatedAsNew").asBoolean()).isTrue();
        assertThat(c.get("captured").get("data").get("chat").get("isNew").asBoolean()).isTrue();
        JsonNode checked = ok(post(id, "check"));
        assertThat(checked.get("conditions").get(0).get("actual").asBoolean()).isTrue();
        assertThat(checked.get("conditions").get(0).get("passed").asBoolean()).isTrue();
        assertThat(checked.get("matched").asBoolean()).isTrue();
        ok(post(id, "end"));

        String id2 = started(byContact(chat.getContactId(), false)).get("id").asString();
        incoming(n1, ASHA, "chat", "Still there?", "Asha");
        worker.tick();
        JsonNode c2 = get(id2);
        assertThat(c2.get("captured").get("treatedAsNew").asBoolean()).isFalse();
        assertThat(c2.get("captured").get("data").get("chat").get("isNew").asBoolean()).isFalse();
        JsonNode checked2 = ok(post(id2, "check"));
        assertThat(checked2.get("conditions").get(0).get("actual").asBoolean()).isFalse();
        assertThat(checked2.get("conditions").get(0).get("passed").asBoolean()).isFalse();
        assertThat(checked2.get("matched").asBoolean()).isFalse();
    }

    @Test
    void draftDoesNotRunLiveDuringTest() throws Exception {
        try (AutomationDevice d = device()) {
            String id = capturedTest();
            assertThat(sent(d)).isEmpty();
            assertThat(liveRuns()).isEmpty();
            assertThat(runsOfTest(id)).singleElement().satisfies(r -> {
                assertThat(r.isTest()).isTrue();
                assertThat(r.getStatus()).isEqualTo(RunStatus.TESTING);
            });
            assertThat(mongo.findAll(AutomationEventRecord.class)).singleElement().satisfies(h -> {
                assertThat(h.getOutcome()).isEqualTo(EventOutcome.DONE);
                assertThat(h.getRuleIds()).isEmpty();
                assertThat(h.getTestIds()).containsExactly(id);
            });
            assertThat(queued()).isEmpty();

            ok(post(id, "actions/0/run"));
            assertThat(sent(d)).containsExactly(WELCOME_TEXT);
        }
    }

    @Test
    void publishedRulesStillRunForTestContact() throws Exception {
        String thanks = template("Thanks", "Thanks for writing");
        AutomationRule live = rule("Thanks", AutomationEventKind.MESSAGE_RECEIVED, RuleStatus.PUBLISHED, null, null,
                send(thanks));
        try (AutomationDevice d = device()) {
            String id = capturedTest();
            assertThat(sent(d)).containsExactly("Thanks for writing");
            assertThat(liveRuns()).singleElement().satisfies(r -> {
                assertThat(r.getRuleId()).isEqualTo(live.getId());
                assertThat(r.isTest()).isFalse();
                assertThat(r.getStatus()).isEqualTo(RunStatus.DONE);
            });
            assertThat(runsOfTest(id)).singleElement().satisfies(r -> {
                assertThat(r.getRuleId()).isEqualTo(draft.getId());
                assertThat(r.isTest()).isTrue();
            });
            assertThat(mongo.findAll(AutomationEventRecord.class)).singleElement().satisfies(h -> {
                assertThat(h.getTestIds()).containsExactly(id);
                // the rules that ran live; the draft's test run is counted under testIds
                assertThat(h.getRuleIds()).containsExactly(live.getId());
            });
        }
    }

    @Test
    void oneTestPerRule() {
        String id = started(byPhone(TYPED)).get("id").asString();
        JsonNode rule = ok(api(HttpMethod.GET, "/rules/" + draft.getId(), manager, null));
        assertThat(rule.get("activeTest").get("id").asString()).isEqualTo(id);
        assertThat(rule.get("activeTest").get("status").asString()).isEqualTo("WAITING");

        ResponseEntity<JsonNode> res = api(HttpMethod.POST, "/rules/" + draft.getId() + "/tests", admin,
                byPhone(ASHA_PHONE));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(res.getBody().get("code").asString()).isEqualTo("AUTOMATION_TEST_RUNNING");
        assertThat(res.getBody().get("testId").asString()).isEqualTo(id);
        assertThat(res.getBody().get("startedById").asString()).isEqualTo(manager.getId());
        assertThat(res.getBody().get("startedByName").asString()).isEqualTo(manager.getName());
        assertThat(res.getBody().get("message").asString())
                .isEqualTo("This rule is already being tested by " + manager.getName() + ".");
        assertThat(mongo.findAll(AutomationTest.class)).hasSize(1);

        ResponseEntity<JsonNode> active = api(HttpMethod.GET, "/rules/" + draft.getId() + "/tests/active", admin,
                null);
        assertThat(ok(active).get("id").asString()).isEqualTo(id);
    }

    @Test
    void activeIsEmptyWithoutATest() {
        ResponseEntity<JsonNode> res = api(HttpMethod.GET, "/rules/" + draft.getId() + "/tests/active", manager,
                null);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void anotherManagerCanEndATest() {
        String id = started(byPhone(TYPED)).get("id").asString();
        JsonNode ended = ok(api(HttpMethod.POST, "/tests/" + id + "/end", admin, null));
        assertThat(ended.get("status").asString()).isEqualTo("ENDED");
        assertThat(ended.get("endReason").asString()).isEqualTo("DONE");
        AutomationTest s = stored(id);
        assertThat(s.isActive()).isFalse();
        assertThat(s.getStatus()).isEqualTo(TestStatus.ENDED);
        assertThat(s.getEndReason()).isEqualTo(TestEndReason.DONE);
        assertState(post(id, "again"), "The test has ended");
        assertThat(api(HttpMethod.GET, "/rules/" + draft.getId() + "/tests/active", manager, null).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<JsonNode> again = api(HttpMethod.POST, "/rules/" + draft.getId() + "/tests", admin,
                byPhone(TYPED));
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(again.getBody().get("startedBy").get("id").asString()).isEqualTo(admin.getId());
    }

    @Test
    void endClosesTheRun() throws Exception {
        try (AutomationDevice d = device()) {
            String id = capturedTest();
            ok(post(id, "actions/0/run"));
            ok(post(id, "end"));
            AutomationRun run = mongo.findById(stored(id).getRunId(), AutomationRun.class);
            assertThat(run.getStatus()).isEqualTo(RunStatus.DONE);
            assertThat(outcomes(run)).containsExactly(StepOutcome.DONE);
            assertThat(run.getFinishedAt()).isEqualTo(NOW);
            assertState(post(id, "actions/1/run"), "The test has ended");
        }
    }

    @Test
    void idleTestEndsAfter30Minutes() {
        String id = started(byPhone(TYPED)).get("id").asString();
        clock.advance(Duration.ofMinutes(31));
        worker.tick();
        JsonNode t = get(id);
        assertThat(t.get("status").asString()).isEqualTo("ENDED");
        assertThat(t.get("endReason").asString()).isEqualTo("IDLE");
        assertThat(stored(id).isActive()).isFalse();
        assertThat(stored(id).getLastActivityAt()).isEqualTo(NOW);
        assertThat(api(HttpMethod.POST, "/rules/" + draft.getId() + "/tests", manager, byPhone(TYPED))
                .getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void watchingKeepsTheTestAlive() {
        String id = started(byPhone(TYPED)).get("id").asString();
        clock.advance(Duration.ofMinutes(20));
        JsonNode watched = get(id);
        assertThat(time(watched.get("idleEndsAt"))).isEqualTo(NOW.plus(Duration.ofMinutes(50)));
        assertThat(stored(id).getLastActivityAt()).isEqualTo(NOW.plus(Duration.ofMinutes(20)));

        clock.advance(Duration.ofMinutes(11));
        worker.tick();
        assertThat(stored(id).getStatus()).isEqualTo(TestStatus.WAITING);
        assertThat(stored(id).isActive()).isTrue();

        clock.advance(Duration.ofMinutes(20));
        worker.tick();
        AutomationTest s = stored(id);
        assertThat(s.getStatus()).isEqualTo(TestStatus.ENDED);
        assertThat(s.getEndReason()).isEqualTo(TestEndReason.IDLE);
        assertThat(get(id).get("endReason").asString()).isEqualTo("IDLE");
    }

    @Test
    void pollingAnIdleTestEndsItInsteadOfReviving() {
        String id = started(byPhone(TYPED)).get("id").asString();
        clock.advance(Duration.ofMinutes(31));
        JsonNode t = get(id);
        assertThat(t.get("status").asString()).isEqualTo("ENDED");
        assertThat(t.get("endReason").asString()).isEqualTo("IDLE");
    }

    @Test
    void preflightBlocksAndWarns() throws Exception {
        crmOn();
        AutomationRule full = rule("Full draft", AutomationEventKind.MESSAGE_RECEIVED, RuleStatus.DRAFT, null, null,
                send(welcome), queueIn(support.getId()), addToCrm());
        String path = "/rules/" + full.getId() + "/tests/preflight";

        JsonNode p = ok(api(HttpMethod.GET, path, manager, null));
        assertThat(p.get("canRun").asBoolean()).isFalse();
        JsonNode checks = p.get("checks");
        assertThat(checks).hasSize(4);
        assertThat(checks.get(0).get("level").asString()).isEqualTo("BLOCKER");
        assertThat(checks.get(0).get("actionIndex").isNull()).isTrue();
        assertThat(checks.get(0).get("message").asString())
                .isEqualTo("No connected extension on any of the brand's numbers");
        assertThat(checks.get(1).get("level").asString()).isEqualTo("WARNING");
        assertThat(checks.get(1).get("actionIndex").asInt()).isZero();
        assertThat(checks.get(1).get("message").asString())
                .isEqualTo("Send template really sends “Welcome” to the test contact");
        assertThat(checks.get(2).get("level").asString()).isEqualTo("WARNING");
        assertThat(checks.get(2).get("actionIndex").asInt()).isEqualTo(1);
        assertThat(checks.get(2).get("message").asString())
                .isEqualTo("Assign chat really reassigns the test contact's chat");
        assertThat(checks.get(3).get("level").asString()).isEqualTo("WARNING");
        assertThat(checks.get(3).get("actionIndex").asInt()).isEqualTo(2);
        assertThat(checks.get(3).get("message").asString())
                .isEqualTo("Add to CRM really creates a CRM contact and a deal for the test contact");

        try (AutomationDevice d = device()) {
            JsonNode ready = ok(api(HttpMethod.GET, path, manager, null));
            assertThat(ready.get("canRun").asBoolean()).isTrue();
            assertThat(ready.get("checks")).hasSize(3)
                    .allSatisfy(c -> assertThat(c.get("level").asString()).isEqualTo("WARNING"));

            MessageTemplate old = mongo.findById(welcome, MessageTemplate.class);
            old.setStatus(TemplateStatus.ARCHIVED);
            mongo.save(old);
            JsonNode archived = ok(api(HttpMethod.GET, path, manager, null));
            assertThat(archived.get("canRun").asBoolean()).isFalse();
            JsonNode first = archived.get("checks").get(0);
            assertThat(first.get("level").asString()).isEqualTo("BLOCKER");
            assertThat(first.get("actionIndex").asInt()).isZero();
            assertThat(first.get("message").asString()).isEqualTo("The template is archived");
            List<String> blockers = new ArrayList<>();
            archived.get("checks").forEach(c -> {
                if (c.get("level").asString().equals("BLOCKER")) blockers.add(c.get("message").asString());
            });
            assertThat(blockers).containsExactly("The template is archived");
        }
    }

    @Test
    void replayRunsTheSameEventAgain() throws Exception {
        try (AutomationDevice d = device()) {
            String id = capturedTest();
            stepThrough(id, d);
            String eventId = stored(id).getEventId();
            String firstRun = stored(id).getRunId();

            JsonNode r = ok(post(id, "replay"));
            assertThat(r.get("status").asString()).isEqualTo("CAPTURED");
            assertThat(r.get("conditions")).isEmpty();
            assertThat(r.get("matched").isNull()).isTrue();
            assertThat(r.get("nextAction").asInt()).isZero();
            assertThat(r.get("captured").get("eventId").asString()).isEqualTo(eventId);
            assertThat(r.get("runId").asString()).isNotEqualTo(firstRun);
            assertThat(r.get("actions").get(0).get("outcome").isNull()).isTrue();
            assertThat(r.get("actions").get(1).get("outcome").isNull()).isTrue();

            List<AutomationRun> runs = runsOfTest(id);
            assertThat(runs).hasSize(2).allSatisfy(x -> {
                assertThat(x.getEventId()).isEqualTo(eventId);
                assertThat(x.isTest()).isTrue();
            });
            assertThat(mongo.findById(firstRun, AutomationRun.class).getStatus()).isEqualTo(RunStatus.DONE);
            AutomationRun second = mongo.findById(r.get("runId").asString(), AutomationRun.class);
            assertThat(second.getStatus()).isEqualTo(RunStatus.TESTING);
            assertThat(second.getSteps()).isEmpty();

            ok(post(id, "check"));
            ok(post(id, "actions/0/run"));
            assertThat(sent(d)).containsExactly(WELCOME_TEXT, WELCOME_TEXT);
            assertThat(mongo.findAll(AutomationEventRecord.class)).singleElement()
                    .satisfies(h -> assertThat(h.getId()).isEqualTo(eventId));
            assertThat(mongo.find(new Query(Criteria.where("eventId").is(eventId)), AutomationRun.class)).hasSize(2)
                    .allSatisfy(x -> assertThat(x.isTest()).isTrue());
        }
    }

    @Test
    void testAgainWaitsForTheNextEvent() {
        String id = capturedTest();
        String eventId = stored(id).getEventId();
        String runId = stored(id).getRunId();
        clock.advance(Duration.ofMinutes(2));

        JsonNode a = ok(post(id, "again"));
        assertThat(a.get("status").asString()).isEqualTo("WAITING");
        assertThat(a.get("captured").isNull()).isTrue();
        assertThat(a.get("conditions")).isEmpty();
        assertThat(a.get("matched").isNull()).isTrue();
        assertThat(a.get("runId").isNull()).isTrue();
        assertThat(a.get("nextAction").isNull()).isTrue();
        assertThat(time(a.get("waitingSince"))).isEqualTo(NOW.plus(Duration.ofMinutes(2)));
        AutomationRun previous = mongo.findById(runId, AutomationRun.class);
        assertThat(previous.getStatus()).isEqualTo(RunStatus.DONE);
        assertThat(previous.getFinishedAt()).isEqualTo(NOW.plus(Duration.ofMinutes(2)));
        assertThat(stored(id).getData()).isNull();
        assertThat(stored(id).getEventId()).isNull();

        incoming(n1, ASHA, "chat", "Me again", "Asha");
        worker.tick();
        JsonNode c = get(id);
        assertThat(c.get("status").asString()).isEqualTo("CAPTURED");
        assertThat(c.get("captured").get("eventId").asString()).isNotEqualTo(eventId);
        assertThat(c.get("captured").get("data").get("message").get("text").asString()).isEqualTo("Me again");
    }

    @Test
    void publishingEndsTheTest() {
        crmOn();
        String id = started(byPhone(TYPED)).get("id").asString();
        JsonNode published = ok(api(HttpMethod.POST, "/rules/" + draft.getId() + "/publish", manager, null));
        assertThat(published.get("activeTest").isNull()).isTrue();
        JsonNode t = get(id);
        assertThat(t.get("status").asString()).isEqualTo("ENDED");
        assertThat(t.get("endReason").asString()).isEqualTo("RULE_PUBLISHED");
        assertThat(stored(id).isActive()).isFalse();
    }

    @Test
    void deletingEndsTheTest() {
        String id = started(byPhone(TYPED)).get("id").asString();
        assertThat(api(HttpMethod.DELETE, "/rules/" + draft.getId(), manager, null).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        AutomationTest s = stored(id);
        assertThat(s.getStatus()).isEqualTo(TestStatus.ENDED);
        assertThat(s.getEndReason()).isEqualTo(TestEndReason.RULE_DELETED);
        assertThat(s.isActive()).isFalse();
        JsonNode t = get(id);
        assertThat(t.get("status").asString()).isEqualTo("ENDED");
        assertThat(t.get("ruleName").isNull()).isTrue();
    }

    @Test
    void publishedRuleCannotBeTested() {
        AutomationRule live = rule("Live", AutomationEventKind.MESSAGE_RECEIVED, RuleStatus.PUBLISHED, null, null,
                send(welcome));
        ResponseEntity<JsonNode> res = start(live, byPhone(TYPED));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(res.getBody().get("code").asString()).isEqualTo("AUTOMATION_RULE_PUBLISHED");
        assertThat(mongo.findAll(AutomationTest.class)).isEmpty();
    }

    @Test
    void typedNumberThatMessagedIsRefused() {
        incoming(n1, TYPED + "@c.us", "chat", "Hello", "Asha");
        ResponseEntity<JsonNode> res = start(draft, byPhone("+" + TYPED));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().get("code").asString()).isEqualTo("VALIDATION");
        assertThat(res.getBody().get("fields").get("phone").asString()).isEqualTo(MESSAGED);
        assertThat(mongo.findAll(AutomationTest.class)).isEmpty();
    }

    @Test
    void laneChangeNeedsAContact() {
        AutomationRule lane = rule("Won", AutomationEventKind.DEAL_LANE_CHANGED, RuleStatus.DRAFT, null, null,
                send(welcome));
        ResponseEntity<JsonNode> res = start(lane, byPhone(TYPED));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().get("fields").get("phone").asString())
                .isEqualTo("Choose an existing contact to test a lane change");
        assertThat(mongo.findAll(AutomationTest.class)).isEmpty();
    }

    @Test
    void startNeedsExactlyOneTarget() {
        WaWebChat chat = incoming(n1, ASHA, "chat", "Hello", "Asha");
        ResponseEntity<JsonNode> none = start(draft, byPhone(null));
        assertThat(none.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(none.getBody().get("fields").get("contactId").asString())
                .isEqualTo("Choose a contact or type a number");
        Map<String, Object> both = byContact(chat.getContactId(), false);
        both.put("phone", TYPED);
        assertThat(start(draft, both).getBody().get("fields").get("contactId").asString())
                .isEqualTo("Choose a contact or type a number");
        ResponseEntity<JsonNode> bad = start(draft, byPhone("12"));
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(bad.getBody().get("fields").get("phone").asString()).isEqualTo(Phones.MESSAGE);
        assertThat(start(draft, byContact("nope", false)).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(api(HttpMethod.POST, "/rules/nope/tests", manager, byPhone(TYPED)).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(mongo.findAll(AutomationTest.class)).isEmpty();
    }

    @Test
    void oneBrokenTestDoesNotStopTheOthers() {
        AutomationTest broken = waitingTest(AutomationEventKind.MESSAGE_RECEIVED, null, ASHA_PHONE);
        broken.setRuleId("gone");
        mongo.save(broken);
        String id = started(byPhone(ASHA_PHONE)).get("id").asString();
        incoming(n1, ASHA, "chat", "Hello", "Asha");
        worker.tick();
        assertThat(get(id).get("status").asString()).isEqualTo("CAPTURED");
        assertThat(stored(broken.getId()).getStatus()).isEqualTo(TestStatus.WAITING);
        assertThat(queued()).isEmpty();
        assertThat(mongo.findAll(AutomationEventRecord.class)).singleElement().satisfies(h -> {
            assertThat(h.getOutcome()).isEqualTo(EventOutcome.DONE);
            assertThat(h.getTestIds()).containsExactly(id);
        });
    }

    @Test
    void testContactsSearch() {
        WaWebChat asha = incoming(n1, ASHA, "chat", "Hello", "Asha");
        WaWebChat ashwin = incoming(n1, "7788991122@lid", "chat", "Hi", "Ashwin");
        incoming(n1, RAVI, "chat", "Hi", "Ravi");

        JsonNode found = ok(api(HttpMethod.GET, "/test-contacts?q=ash", manager, null));
        assertThat(found).hasSize(2);
        Map<String, JsonNode> byName = new HashMap<>();
        found.forEach(c -> byName.put(c.get("name").asString(), c));
        JsonNode a = byName.get("Asha");
        assertThat(a.get("contactId").asString()).isEqualTo(asha.getContactId());
        assertThat(a.get("phone").asString()).isEqualTo(ASHA_PHONE);
        assertThat(a.get("numbers")).hasSize(1);
        assertThat(a.get("numbers").get(0).get("id").asString()).isEqualTo(n1.getId());
        assertThat(a.get("numbers").get(0).get("number").asString()).isEqualTo(n1.getNumber());
        JsonNode w = byName.get("Ashwin");
        assertThat(w.get("contactId").asString()).isEqualTo(ashwin.getContactId());
        assertThat(w.get("phone").isNull()).isTrue();

        JsonNode byPhoneDigits = ok(api(HttpMethod.GET, "/test-contacts?q=98111 00002", manager, null));
        assertThat(byPhoneDigits).singleElement()
                .satisfies(c -> assertThat(c.get("name").asString()).isEqualTo("Ravi"));
    }

    @Test
    void agentIsForbidden() {
        ResponseEntity<JsonNode> res = api(HttpMethod.POST, "/rules/" + draft.getId() + "/tests", agent,
                byPhone(TYPED));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        String id = started(byPhone(TYPED)).get("id").asString();
        assertThat(api(HttpMethod.GET, "/tests/" + id, agent, null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(api(HttpMethod.GET, "/test-contacts?q=a", agent, null).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(api(HttpMethod.POST, "/tests/" + id + "/end", agent, null).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(stored(id).isActive()).isTrue();
    }

    @Test
    void anotherBrandCannotSeeTheTest() {
        String id = started(byPhone(TYPED)).get("id").asString();
        assertThat(call(HttpMethod.GET, "/api/workspaces/" + other.getId() + "/automations/tests/" + id, admin, null)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
