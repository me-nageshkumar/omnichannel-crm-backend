package me.nageshk.omnichannel.service.automation;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import me.nageshk.omnichannel.common.api.NotFoundException;
import me.nageshk.omnichannel.common.api.ValidationException;
import me.nageshk.omnichannel.common.config.OmnichannelProperties;
import me.nageshk.omnichannel.db.automation.AutomationAction;
import me.nageshk.omnichannel.db.automation.AutomationEvent;
import me.nageshk.omnichannel.db.automation.AutomationEventKind;
import me.nageshk.omnichannel.db.automation.AutomationRule;
import me.nageshk.omnichannel.db.automation.AutomationRuleRepository;
import me.nageshk.omnichannel.db.automation.AutomationRun;
import me.nageshk.omnichannel.db.automation.AutomationRunRepository;
import me.nageshk.omnichannel.db.automation.AutomationTest;
import me.nageshk.omnichannel.db.automation.AutomationTestRepository;
import me.nageshk.omnichannel.db.automation.ConditionResult;
import me.nageshk.omnichannel.db.automation.EventData;
import me.nageshk.omnichannel.db.automation.RuleStatus;
import me.nageshk.omnichannel.db.automation.RunStatus;
import me.nageshk.omnichannel.db.automation.RunStep;
import me.nageshk.omnichannel.db.automation.StepOutcome;
import me.nageshk.omnichannel.db.automation.TestEndReason;
import me.nageshk.omnichannel.db.automation.TestStatus;
import me.nageshk.omnichannel.db.contact.Contact;
import me.nageshk.omnichannel.db.contact.ContactRepository;
import me.nageshk.omnichannel.db.inbox.MessageDirection;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import me.nageshk.omnichannel.db.inbox.WaWebMessage;
import me.nageshk.omnichannel.db.phone.PhoneNumber;
import me.nageshk.omnichannel.db.phone.PhoneNumberRepository;
import me.nageshk.omnichannel.db.template.MessageTemplate;
import me.nageshk.omnichannel.db.template.MessageTemplateRepository;
import me.nageshk.omnichannel.db.user.User;
import me.nageshk.omnichannel.db.user.UserRepository;
import me.nageshk.omnichannel.security.auth.AuthenticatedUser;
import me.nageshk.omnichannel.service.contact.Phones;
import me.nageshk.omnichannel.service.inbox.InboxDevices;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * Tests a draft rule on one chosen contact: the test waits for that contact's next event, then the manager checks
 * the conditions and runs or skips the actions one at a time (they really happen). Published rules keep running for
 * the contact as usual. A test nobody watches ends after {@code testIdle}; the console's polling is its heartbeat.
 */
@Service
public class AutomationTestService {

    private static final Logger log = LoggerFactory.getLogger(AutomationTestService.class);

    static final String NO_TARGET = "Choose a contact or type a number";
    static final String MESSAGED = "This number has already messaged you. Choose it as an existing contact and switch "
            + "on “Treat this chat as new”.";
    static final String LANE_NEEDS_CONTACT = "Choose an existing contact to test a lane change";
    static final String NO_DEVICE = "No connected extension on any of the brand's numbers";
    static final String SKIPPED = "Skipped in the test";
    static final String WOULD_RETRY = " (live, this would be retried)";
    static final String WAIT = "Wait for the event first";
    static final String ENDED = "The test has ended";
    static final String ALL_CHECKED = "Every condition is checked";
    static final String ALL_DONE = "Every action is done";
    private static final int CONTACT_LIMIT = 20;
    private static final Pattern ACTION_KEY = Pattern.compile("^actions\\[(\\d+)]");

    private final MongoTemplate mongo;
    private final AutomationTestRepository tests;
    private final AutomationRuleRepository rules;
    private final AutomationRunRepository runs;
    private final ContactRepository contacts;
    private final PhoneNumberRepository numbers;
    private final MessageTemplateRepository templates;
    private final UserRepository users;
    private final RulePublishValidator publishValidator;
    private final ActionRunner actions;
    private final AutomationCrm crm;
    private final AutomationViews views;
    private final InboxDevices devices;
    private final OmnichannelProperties props;
    private final Clock clock;

    public AutomationTestService(MongoTemplate mongo, AutomationTestRepository tests, AutomationRuleRepository rules,
            AutomationRunRepository runs, ContactRepository contacts, PhoneNumberRepository numbers,
            MessageTemplateRepository templates, UserRepository users, RulePublishValidator publishValidator,
            ActionRunner actions, AutomationCrm crm, AutomationViews views, InboxDevices devices,
            OmnichannelProperties props, Clock clock) {
        this.mongo = mongo;
        this.tests = tests;
        this.rules = rules;
        this.runs = runs;
        this.contacts = contacts;
        this.numbers = numbers;
        this.templates = templates;
        this.users = users;
        this.publishValidator = publishValidator;
        this.actions = actions;
        this.crm = crm;
        this.views = views;
        this.devices = devices;
        this.props = props;
        this.clock = clock;
    }

    // ---- start, find, end ----

    public TestView start(AuthenticatedUser me, String workspaceId, String ruleId, TestStartRequest r) {
        AutomationRule rule = requireRule(workspaceId, ruleId);
        if (rule.getStatus() == RuleStatus.PUBLISHED) throw AutomationErrors.published();
        Instant now = clock.instant();
        endIdle(now);
        Optional<AutomationTest> running = tests.findByRuleIdAndActiveTrue(rule.getId());
        if (running.isPresent()) throw AutomationErrors.testRunning(running.get());
        boolean byContact = !blank(r.contactId());
        if (byContact == !blank(r.phone())) throw new ValidationException("contactId", NO_TARGET);

        AutomationTest t = new AutomationTest();
        if (byContact) {
            Contact c = contacts.findByIdAndWorkspaceId(r.contactId(), workspaceId)
                    .orElseThrow(() -> new NotFoundException("contact not found"));
            t.setTargetContactId(c.getId());
            t.setTargetPhone(c.getPhone());
            t.setTargetName(c.getName());
        } else {
            if (rule.getEvent() == AutomationEventKind.DEAL_LANE_CHANGED) {
                throw new ValidationException("phone", LANE_NEEDS_CONTACT);
            }
            String phone = Phones.normalise(r.phone().trim());
            if (!Phones.isValid(phone)) throw new ValidationException("phone", Phones.MESSAGE);
            Contact c = contacts.findByWorkspaceIdAndPhone(workspaceId, phone).orElse(null);
            if (c != null && hasWritten(workspaceId, c.getId())) throw new ValidationException("phone", MESSAGED);
            t.setTargetPhone(phone);
            if (c != null) {
                t.setTargetContactId(c.getId());
                t.setTargetName(c.getName());
            }
        }
        t.setCustomerId(rule.getCustomerId());
        t.setWorkspaceId(workspaceId);
        t.setRuleId(rule.getId());
        t.setEvent(rule.getEvent());
        t.setActive(true);
        t.setStatus(TestStatus.WAITING);
        t.setStartedByUserId(me.userId());
        t.setStartedByName(users.findById(me.userId()).map(User::getName).orElse(me.getUsername()));
        t.setTreatAsNew(r.treatAsNew());
        t.setConditionResults(new ArrayList<>());
        t.setWaitingSince(now);
        t.setLastActivityAt(now);
        try {
            t = tests.insert(t);
        } catch (DuplicateKeyException raced) {
            throw tests.findByRuleIdAndActiveTrue(rule.getId()).map(AutomationErrors::testRunning)
                    .orElseThrow(() -> raced);
        }
        return view(t);
    }

    /** The test; looking at an active test keeps it alive. */
    public TestView get(String workspaceId, String testId) {
        return view(heartbeat(require(workspaceId, testId)));
    }

    /** The rule's test in progress, kept alive by the look. */
    public Optional<TestView> active(String workspaceId, String ruleId) {
        AutomationRule rule = requireRule(workspaceId, ruleId);
        return tests.findByRuleIdAndActiveTrue(rule.getId()).filter(t -> workspaceId.equals(t.getWorkspaceId()))
                .map(this::heartbeat).filter(AutomationTest::isActive).map(this::view);
    }

    /** Ends the test, whoever started it; an ended test is returned as it is. */
    public TestView end(String workspaceId, String testId) {
        AutomationTest t = require(workspaceId, testId);
        if (!t.isActive()) return view(t);
        AutomationTest ended = endWhere(Criteria.where("_id").is(t.getId()).and("active").is(true),
                TestEndReason.DONE);
        return view(ended != null ? ended : require(workspaceId, testId));
    }

    /** Ends the rule's test in progress, if any, with the reason. */
    public void endActive(String ruleId, TestEndReason reason) {
        endWhere(Criteria.where("ruleId").is(ruleId).and("active").is(true), reason);
    }

    /** Ends every test nobody has looked at for {@code testIdle}; returns how many ended. */
    public int endIdle(Instant now) {
        Instant cutoff = now.minus(props.automation().testIdle());
        int ended = 0;
        for (AutomationTest t : tests.findByActiveTrueAndLastActivityAtBefore(cutoff)) {
            try {
                if (endIdle(t, cutoff) != null) ended++;
            } catch (RuntimeException e) {
                log.warn("could not end idle automation test {}: {}", t.getId(), e.toString());
            }
        }
        return ended;
    }

    private AutomationTest endIdle(AutomationTest t, Instant cutoff) {
        return endWhere(Criteria.where("_id").is(t.getId()).and("active").is(true).and("lastActivityAt").lt(cutoff),
                TestEndReason.IDLE);
    }

    /** Ends the one active test the criteria find and closes its run; null when none was active. */
    private AutomationTest endWhere(Criteria which, TestEndReason reason) {
        AutomationTest ended = mongo.findAndModify(new Query(which),
                new Update().set("active", false).set("status", TestStatus.ENDED).set("endReason", reason)
                        .inc("version", 1),
                FindAndModifyOptions.options().returnNew(true), AutomationTest.class);
        if (ended != null) closeRun(ended.getRunId());
        return ended;
    }

    /** An active test seen past its idle time ends instead of coming back to life; otherwise it lives on. */
    private AutomationTest heartbeat(AutomationTest t) {
        if (!t.isActive()) return t;
        Instant now = clock.instant();
        Instant cutoff = now.minus(props.automation().testIdle());
        if (t.getLastActivityAt() != null && t.getLastActivityAt().isBefore(cutoff)) {
            AutomationTest ended = endIdle(t, cutoff);
            return ended != null ? ended : tests.findById(t.getId()).orElse(t);
        }
        mongo.updateFirst(new Query(Criteria.where("_id").is(t.getId()).and("active").is(true)),
                new Update().set("lastActivityAt", now), AutomationTest.class);
        t.setLastActivityAt(now);
        return t;
    }

    // ---- the event ----

    /**
     * Offers a processed event to the waiting tests of its brand and kind; each test that targets its contact takes
     * it. A test that cannot (ended that instant, its rule gone) is logged and skipped: the event never fails for a
     * test. Returns the ids of the tests that took it.
     */
    public List<String> capture(AutomationEvent e) {
        List<String> took = new ArrayList<>();
        List<AutomationTest> waiting;
        try {
            waiting = tests.findByWorkspaceIdAndEventAndStatus(e.getWorkspaceId(), e.getKind(), TestStatus.WAITING);
        } catch (RuntimeException ex) {
            log.warn("could not offer automation event {} to the tests: {}", e.getId(), ex.toString());
            return took;
        }
        for (AutomationTest t : waiting) {
            if (!t.isActive() || !targets(t, e)) continue;
            try {
                capture(t, e);
                took.add(t.getId());
            } catch (RuntimeException ex) {
                log.warn("automation test {} could not take event {}: {}", t.getId(), e.getId(), ex.toString());
            }
        }
        return took;
    }

    private static boolean targets(AutomationTest t, AutomationEvent e) {
        return (t.getTargetContactId() != null && t.getTargetContactId().equals(e.getContactId()))
                || (t.getTargetPhone() != null && t.getTargetPhone().equals(e.getContactPhone()));
    }

    private void capture(AutomationTest t, AutomationEvent e) {
        AutomationRule rule = rules.findByIdAndWorkspaceId(t.getRuleId(), t.getWorkspaceId())
                .orElseThrow(() -> new IllegalStateException("the test's rule is gone"));
        Instant now = clock.instant();
        EventData data = t.isTreatAsNew() ? asNew(e.getData()) : e.getData();
        AutomationRun run = runs.insert(newRun(t, rule, e.getId(), data, e.getContactId(), e.getContactPhone(), now));
        t.setStatus(TestStatus.CAPTURED);
        t.setEventId(e.getId());
        t.setCapturedAt(now);
        t.setData(data);
        startOver(t, rule, run, now);
        try {
            tests.save(t);
        } catch (RuntimeException ex) {
            runs.deleteById(run.getId());
            throw ex;
        }
    }

    /** Fresh condition results and actions on a new run. */
    private static void startOver(AutomationTest t, AutomationRule rule, AutomationRun run, Instant now) {
        t.setConditionResults(new ArrayList<>());
        t.setMatched(rule.getConditions() == null || ConditionEvaluator.leaves(rule.getConditions()).isEmpty()
                ? Boolean.TRUE : null);
        t.setNextAction(actionsOf(rule).isEmpty() ? null : 0);
        t.setRunId(run.getId());
        t.setLastActivityAt(now);
    }

    private static EventData asNew(EventData d) {
        if (d == null || d.chat() == null) return d;
        EventData.ChatData c = d.chat();
        return new EventData(new EventData.ChatData(c.id(), true, c.status(), c.assignedUser(), c.assignedTeam(), false),
                d.message(), d.number(), d.contact(), d.deal(), d.pipeline(), d.fromLane(), d.toLane());
    }

    private static AutomationRun newRun(AutomationTest t, AutomationRule rule, String eventId, EventData d,
            String contactId, String contactPhone, Instant now) {
        AutomationRun run = new AutomationRun();
        run.setCustomerId(t.getCustomerId());
        run.setWorkspaceId(t.getWorkspaceId());
        run.setEventId(eventId);
        run.setEventKind(t.getEvent());
        run.setRuleId(rule.getId());
        run.setRuleName(rule.getName());
        run.setTest(true);
        run.setTestId(t.getId());
        run.setStatus(RunStatus.TESTING);
        EventData.ContactData contact = d == null ? null : d.contact();
        run.setContactId(contact != null && contact.id() != null ? contact.id() : contactId);
        run.setContactName(contact == null ? null : contact.name());
        run.setContactPhone(contact != null && contact.phone() != null ? contact.phone() : contactPhone);
        run.setDealId(d == null || d.deal() == null ? null : d.deal().id());
        run.setDealTitle(d == null || d.deal() == null ? null : d.deal().title());
        run.setSummary(d == null ? null : EventSummaries.of(t.getEvent(), d));
        run.setSteps(new ArrayList<>());
        run.setStartedAt(now);
        return run;
    }

    // ---- stepping ----

    /** Checks the next condition of the rule as it is now against the captured event. */
    public TestView check(String workspaceId, String testId) {
        AutomationTest t = require(workspaceId, testId);
        requireCaptured(t);
        AutomationRule rule = requireRuleOf(t);
        List<ConditionEvaluator.Leaf> leaves = ConditionEvaluator.leaves(rule.getConditions());
        List<ConditionResult> done = new ArrayList<>(
                t.getConditionResults() == null ? List.of() : t.getConditionResults());
        if (done.size() >= leaves.size()) throw AutomationErrors.testState(ALL_CHECKED);
        Map<String, Object> values = AutomationFields.values(t.getData(), inCrmNow(t));
        done.add(ConditionEvaluator.check(leaves.get(done.size()), values));
        if (done.size() == leaves.size()) {
            Map<String, Boolean> passed = new HashMap<>();
            for (int i = 0; i < leaves.size(); i++) passed.put(leaves.get(i).path(), done.get(i).passed());
            t.setMatched(ConditionEvaluator.combine(rule.getConditions(), passed));
        }
        t.setConditionResults(done);
        t.setLastActivityAt(clock.instant());
        return view(tests.save(t));
    }

    /** Message events see the CRM as it is now, as a live run does; a deal is in the CRM. */
    private boolean inCrmNow(AutomationTest t) {
        if (t.getEvent() == AutomationEventKind.DEAL_LANE_CHANGED) return true;
        EventData d = t.getData();
        String contactId = d == null || d.contact() == null ? null : d.contact().id();
        String numberId = d == null || d.number() == null ? null : d.number().id();
        return contactId != null && numberId != null && crm.inCrm(t.getWorkspaceId(), contactId, numberId);
    }

    /** Runs the action for real; it must be the next one. */
    public TestView runAction(String workspaceId, String testId, int index) {
        return step(workspaceId, testId, index, true);
    }

    /** Skips the action; it must be the next one. */
    public TestView skipAction(String workspaceId, String testId, int index) {
        return step(workspaceId, testId, index, false);
    }

    private TestView step(String workspaceId, String testId, int index, boolean run) {
        AutomationTest t = require(workspaceId, testId);
        requireCaptured(t);
        AutomationRule rule = requireRuleOf(t);
        List<AutomationAction> list = actionsOf(rule);
        if (t.getNextAction() == null || index != t.getNextAction() || index >= list.size()) throw outOfTurn(t);
        Integer next = index + 1 < list.size() ? index + 1 : null;
        Instant now = clock.instant();
        // take the turn first, so a second click cannot run the action twice
        AutomationTest claimed = mongo.findAndModify(
                new Query(Criteria.where("_id").is(t.getId()).and("active").is(true).and("status")
                        .is(TestStatus.CAPTURED).and("nextAction").is(index)),
                new Update().set("nextAction", next).set("lastActivityAt", now).inc("version", 1),
                FindAndModifyOptions.options().returnNew(true), AutomationTest.class);
        if (claimed == null) {
            AutomationTest fresh = require(workspaceId, testId);
            requireCaptured(fresh);
            throw outOfTurn(fresh);
        }
        AutomationAction a = list.get(index);
        String summary = views.actionSummaries(claimed.getWorkspaceId(), list).get(index);
        RunStep step;
        if (run) {
            ActionContext ctx = new ActionContext(claimed.getWorkspaceId(), claimed.getCustomerId(),
                    claimed.getEvent(), claimed.getEventId(), claimed.getData(), rule.getId(), rule.getName(), true);
            ActionResult r = actions.run(ctx, a);
            if (r.outcome() == StepOutcome.RETRYING) r = ActionResult.failed(r.reason() + WOULD_RETRY);
            step = new RunStep(index, a.type(), summary, r.outcome(), r.reason(), clock.instant());
        } else {
            step = new RunStep(index, a.type(), summary, StepOutcome.SKIPPED, SKIPPED, now);
        }
        if (claimed.getRunId() != null) {
            mongo.updateFirst(new Query(Criteria.where("_id").is(claimed.getRunId())),
                    new Update().set("steps." + index, step), AutomationRun.class);
            if (next == null) closeRun(claimed.getRunId());
        }
        return view(claimed);
    }

    private static RuntimeException outOfTurn(AutomationTest t) {
        return AutomationErrors.testState(
                t.getNextAction() == null ? ALL_DONE : "Run or skip action " + (t.getNextAction() + 1) + " first");
    }

    /** Waits for the contact's next event; the run so far is closed. */
    public TestView again(String workspaceId, String testId) {
        AutomationTest t = require(workspaceId, testId);
        if (!t.isActive()) throw AutomationErrors.testState(ENDED);
        Instant now = clock.instant();
        String previousRun = t.getRunId();
        t.setStatus(TestStatus.WAITING);
        t.setEventId(null);
        t.setCapturedAt(null);
        t.setData(null);
        t.setConditionResults(new ArrayList<>());
        t.setMatched(null);
        t.setNextAction(null);
        t.setRunId(null);
        t.setWaitingSince(now);
        t.setLastActivityAt(now);
        AutomationTest saved = tests.save(t);
        closeRun(previousRun);
        return view(saved);
    }

    /** Runs the captured event again from its first condition, on a new run; the run so far is closed. */
    public TestView replay(String workspaceId, String testId) {
        AutomationTest t = require(workspaceId, testId);
        requireCaptured(t);
        AutomationRule rule = requireRuleOf(t);
        Instant now = clock.instant();
        String previousRun = t.getRunId();
        AutomationRun run = runs.insert(
                newRun(t, rule, t.getEventId(), t.getData(), t.getTargetContactId(), t.getTargetPhone(), now));
        startOver(t, rule, run, now);
        AutomationTest saved;
        try {
            saved = tests.save(t);
        } catch (RuntimeException ex) {
            runs.deleteById(run.getId());
            throw ex;
        }
        closeRun(previousRun);
        return view(saved);
    }

    /** A run still TESTING ends with the steps it has: FAILED if one failed, INTERRUPTED if one was, else DONE. */
    private void closeRun(String runId) {
        if (runId == null) return;
        AutomationRun run = runs.findById(runId).orElse(null);
        if (run == null || run.getStatus() != RunStatus.TESTING) return;
        boolean failed = false;
        boolean interrupted = false;
        for (RunStep s : run.getSteps() == null ? List.<RunStep>of() : run.getSteps()) {
            if (s == null) continue;
            failed |= s.outcome() == StepOutcome.FAILED;
            interrupted |= s.outcome() == StepOutcome.INTERRUPTED;
        }
        RunStatus status = failed ? RunStatus.FAILED : interrupted ? RunStatus.INTERRUPTED : RunStatus.DONE;
        mongo.updateFirst(new Query(Criteria.where("_id").is(runId).and("status").is(RunStatus.TESTING)),
                new Update().set("status", status).set("finishedAt", clock.instant()), AutomationRun.class);
    }

    // ---- preflight and contacts ----

    /** What would stop the rule's test, and what its actions will really do to the test contact. */
    public PreflightView preflight(String workspaceId, String ruleId) {
        AutomationRule rule = requireRule(workspaceId, ruleId);
        List<PreflightView.Check> checks = new ArrayList<>();
        publishValidator.problems(rule).forEach((key, message) -> checks
                .add(new PreflightView.Check(PreflightView.Level.BLOCKER, actionIndex(key), message)));
        boolean ready = numbers.findByWorkspaceIdOrderByNumber(workspaceId).stream()
                .anyMatch(n -> devices.isReady(workspaceId, n.getId()));
        if (!ready) checks.add(new PreflightView.Check(PreflightView.Level.BLOCKER, null, NO_DEVICE));
        List<AutomationAction> list = actionsOf(rule);
        for (int i = 0; i < list.size(); i++) {
            AutomationAction a = list.get(i);
            if (a == null || a.type() == null) continue;
            String message = switch (a.type()) {
                case SEND_TEMPLATE -> "Send template really sends “" + templateName(workspaceId, a.templateId())
                        + "” to the test contact";
                case ASSIGN_CHAT -> "Assign chat really reassigns the test contact's chat";
                case ADD_TO_CRM -> "Add to CRM really creates a CRM contact and a deal for the test contact";
                case START_CHATBOT -> "Start chatbot really starts the chatbot on the test contact's chat";
            };
            checks.add(new PreflightView.Check(PreflightView.Level.WARNING, i, message));
        }
        boolean canRun = checks.stream().noneMatch(c -> c.level() == PreflightView.Level.BLOCKER);
        return new PreflightView(canRun, checks);
    }

    private static Integer actionIndex(String key) {
        Matcher m = ACTION_KEY.matcher(key);
        return m.find() ? Integer.valueOf(m.group(1)) : null;
    }

    private String templateName(String workspaceId, String templateId) {
        if (templateId == null) return "?";
        return templates.findByIdAndWorkspaceId(templateId, workspaceId).map(MessageTemplate::getName)
                .orElse(templateId);
    }

    /** Up to 20 contacts of the brand whose name or phone contains {@code q}, with the numbers they have chats on. */
    public List<TestContactView> contacts(String workspaceId, String q) {
        Criteria c = Criteria.where("workspaceId").is(workspaceId);
        String text = q == null ? "" : q.trim();
        if (!text.isEmpty()) {
            List<Criteria> any = new ArrayList<>();
            any.add(Criteria.where("name").regex(Pattern.quote(text), "i"));
            String digits = text.replaceAll("\\D", "");
            if (!digits.isEmpty()) any.add(Criteria.where("phone").regex(Pattern.quote(digits)));
            c = c.orOperator(any.toArray(new Criteria[0]));
        }
        List<Contact> found = mongo.find(new Query(c)
                .with(Sort.by(Sort.Order.desc("lastMessageAt"), Sort.Order.asc("name"), Sort.Order.asc("_id")))
                .limit(CONTACT_LIMIT), Contact.class);
        if (found.isEmpty()) return List.of();
        Query chatQuery = new Query(Criteria.where("workspaceId").is(workspaceId).and("contactId")
                .in(found.stream().map(Contact::getId).toList()));
        chatQuery.fields().include("contactId", "phoneNumberId");
        Map<String, Set<String>> numbersOf = new HashMap<>();
        for (WaWebChat chat : mongo.find(chatQuery, WaWebChat.class)) {
            numbersOf.computeIfAbsent(chat.getContactId(), k -> new LinkedHashSet<>()).add(chat.getPhoneNumberId());
        }
        List<PhoneNumber> brandNumbers = numbers.findByWorkspaceIdOrderByNumber(workspaceId);
        List<TestContactView> out = new ArrayList<>();
        for (Contact contact : found) {
            Set<String> mine = numbersOf.getOrDefault(contact.getId(), Set.of());
            out.add(new TestContactView(contact.getId(), contact.getName(), contact.getPhone(),
                    brandNumbers.stream().filter(n -> mine.contains(n.getId())).map(AutomationTestService::ref)
                            .toList()));
        }
        return out;
    }

    /** Whether one of the contact's chats holds a real message from them. */
    private boolean hasWritten(String workspaceId, String contactId) {
        Query chatQuery = new Query(Criteria.where("workspaceId").is(workspaceId).and("contactId").is(contactId));
        chatQuery.fields().include("_id");
        List<String> chatIds = mongo.find(chatQuery, WaWebChat.class).stream().map(WaWebChat::getId).toList();
        return !chatIds.isEmpty() && mongo.exists(new Query(Criteria.where("chatId").in(chatIds).and("direction")
                .is(MessageDirection.IN).and("type").in(MessageKinds.REAL_TYPES)), WaWebMessage.class);
    }

    // ---- views and lookups ----

    private TestView view(AutomationTest t) {
        AutomationRule rule = t.getRuleId() == null ? null
                : rules.findByIdAndWorkspaceId(t.getRuleId(), t.getWorkspaceId()).orElse(null);
        List<AutomationAction> list = rule == null ? List.of() : actionsOf(rule);
        AutomationRun run = t.getRunId() == null ? null : runs.findById(t.getRunId()).orElse(null);
        List<String> summaries = views.actionSummaries(t.getWorkspaceId(), list);
        List<StepView> steps = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            RunStep s = run == null || run.getSteps() == null || i >= run.getSteps().size() ? null
                    : run.getSteps().get(i);
            AutomationAction a = list.get(i);
            // a recorded step keeps the label it was run under; the steps still to come show the rule as it is
            String summary = s == null ? summaries.get(i) : AutomationViews.stepSummary(s);
            steps.add(new StepView(i, a == null ? null : a.type(), summary, s == null ? null : s.outcome(),
                    s == null ? null : s.reason(), s == null ? null : s.at()));
        }
        List<TestView.NumberRef> where = t.getEvent() != AutomationEventKind.MESSAGE_RECEIVED ? List.of()
                : numbers.findByWorkspaceIdOrderByNumber(t.getWorkspaceId()).stream()
                        .filter(n -> n.getDisabled() == null).map(AutomationTestService::ref).toList();
        TestView.Captured captured = t.getEventId() == null ? null
                : new TestView.Captured(t.getEventId(), t.getCapturedAt(), t.getData(), t.isTreatAsNew());
        Instant idleEndsAt = t.getLastActivityAt() == null ? null
                : t.getLastActivityAt().plus(props.automation().testIdle());
        return new TestView(t.getId(), t.getRuleId(), rule == null ? null : rule.getName(), t.getEvent(),
                t.getStatus(), t.getEndReason(), new AutomationRef(t.getStartedByUserId(), t.getStartedByName()),
                new TestView.Target(t.getTargetContactId(), t.getTargetName(), t.getTargetPhone()), t.isTreatAsNew(),
                where, t.getWaitingSince(), idleEndsAt, captured,
                rule == null ? 0 : ConditionEvaluator.leaves(rule.getConditions()).size(),
                t.getConditionResults() == null ? List.of() : t.getConditionResults(), t.getMatched(), steps,
                t.getNextAction(), t.getRunId());
    }

    private static TestView.NumberRef ref(PhoneNumber n) {
        return new TestView.NumberRef(n.getId(), n.getNumber(), n.getLabel());
    }

    private AutomationTest require(String workspaceId, String testId) {
        return tests.findByIdAndWorkspaceId(testId, workspaceId)
                .orElseThrow(() -> new NotFoundException("test not found"));
    }

    private AutomationRule requireRule(String workspaceId, String ruleId) {
        return rules.findByIdAndWorkspaceId(ruleId, workspaceId)
                .orElseThrow(() -> new NotFoundException("rule not found"));
    }

    /** Publishing or deleting a rule ends its test, so an active test's rule is there. */
    private AutomationRule requireRuleOf(AutomationTest t) {
        return rules.findByIdAndWorkspaceId(t.getRuleId(), t.getWorkspaceId())
                .orElseThrow(() -> AutomationErrors.testState(ENDED));
    }

    private static void requireCaptured(AutomationTest t) {
        if (!t.isActive() || t.getStatus() == TestStatus.ENDED) throw AutomationErrors.testState(ENDED);
        if (t.getStatus() != TestStatus.CAPTURED) throw AutomationErrors.testState(WAIT);
    }

    private static List<AutomationAction> actionsOf(AutomationRule rule) {
        return rule.getActions() == null ? List.of() : rule.getActions();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
