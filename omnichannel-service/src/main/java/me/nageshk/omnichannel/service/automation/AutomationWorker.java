package me.nageshk.omnichannel.service.automation;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import me.nageshk.omnichannel.common.config.OmnichannelProperties;
import me.nageshk.omnichannel.db.automation.AutomationEvent;
import me.nageshk.omnichannel.db.automation.AutomationEventRecord;
import me.nageshk.omnichannel.db.automation.AutomationRule;
import me.nageshk.omnichannel.db.automation.AutomationRuleRepository;
import me.nageshk.omnichannel.db.automation.AutomationRun;
import me.nageshk.omnichannel.db.automation.AutomationRunRepository;
import me.nageshk.omnichannel.db.automation.EventOutcome;
import me.nageshk.omnichannel.db.automation.EventStatus;
import me.nageshk.omnichannel.db.automation.RuleStatus;
import me.nageshk.omnichannel.db.automation.RunStatus;
import me.nageshk.omnichannel.db.automation.RunStep;
import me.nageshk.omnichannel.db.automation.StepOutcome;
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
 * The queue's consumer: claims the oldest due event with a lease, runs the brand's published rules of its kind in
 * {@link RuleOrder}, offers it to the tests waiting for its contact, and moves it to the history. A send waiting for
 * its device puts the event back for a retry, and the later rules wait with it. Each tick first ends the idle tests.
 */
@Service
public class AutomationWorker {

    private static final Logger log = LoggerFactory.getLogger(AutomationWorker.class);

    private final MongoTemplate mongo;
    private final AutomationRuleRepository rules;
    private final AutomationRunRepository runs;
    private final RuleRunner ruleRunner;
    private final AutomationTestService tests;
    private final OmnichannelProperties props;
    private final Clock clock;
    private final AtomicBoolean ticking = new AtomicBoolean();

    AutomationWorker(MongoTemplate mongo, AutomationRuleRepository rules, AutomationRunRepository runs,
            RuleRunner ruleRunner, AutomationTestService tests, OmnichannelProperties props, Clock clock) {
        this.mongo = mongo;
        this.rules = rules;
        this.runs = runs;
        this.ruleRunner = ruleRunner;
        this.tests = tests;
        this.props = props;
        this.clock = clock;
    }

    /** Processes up to {@code batch} due events; an overlapping tick does nothing. Returns the events processed. */
    public int tick() {
        if (!ticking.compareAndSet(false, true)) return 0;
        try {
            try {
                tests.endIdle(clock.instant());
            } catch (RuntimeException ex) {
                log.warn("could not end the idle automation tests: {}", ex.toString());
            }
            Set<String> seen = new HashSet<>();
            int done = 0;
            while (done < props.automation().batch()) {
                Optional<AutomationEvent> claimed = claim(seen);
                if (claimed.isEmpty()) break;
                AutomationEvent e = claimed.get();
                seen.add(e.getId());
                done++;
                try {
                    process(e);
                } catch (RuntimeException ex) {
                    // the lease runs out and the event is claimed again
                    log.warn("could not settle automation event {}", e.getId(), ex);
                }
            }
            return done;
        } finally {
            ticking.set(false);
        }
    }

    /** Leases the oldest event that is due, or whose lease ran out, counting the attempt. */
    public Optional<AutomationEvent> claim() {
        return claim(Set.of());
    }

    /** As {@link #claim()}, leaving out the events this tick has handled already. */
    Optional<AutomationEvent> claim(Collection<String> skip) {
        Instant now = clock.instant();
        Criteria due = new Criteria().orOperator(
                new Criteria().andOperator(Criteria.where("status").is(EventStatus.PENDING),
                        new Criteria().orOperator(Criteria.where("notBefore").exists(false),
                                Criteria.where("notBefore").is(null), Criteria.where("notBefore").lte(now))),
                new Criteria().andOperator(Criteria.where("status").is(EventStatus.RUNNING),
                        Criteria.where("leaseUntil").lt(now)));
        Query q = new Query(skip.isEmpty() ? due : new Criteria().andOperator(due, Criteria.where("_id").nin(skip)))
                .with(Sort.by(Sort.Direction.ASC, "createdAt", "_id"));
        Update u = new Update().set("status", EventStatus.RUNNING)
                .set("leaseUntil", now.plus(props.automation().lease())).inc("attempts", 1);
        return Optional.ofNullable(
                mongo.findAndModify(q, u, FindAndModifyOptions.options().returnNew(true), AutomationEvent.class));
    }

    /** Runs the event's rules in order; settles it as done, for a retry, or (out of attempts) as failed. */
    public void process(AutomationEvent e) {
        try {
            List<AutomationRule> published = new ArrayList<>(
                    rules.findByWorkspaceIdAndEventAndStatus(e.getWorkspaceId(), e.getKind(), RuleStatus.PUBLISHED));
            published.sort(RuleOrder.COMPARATOR);
            for (AutomationRule rule : published) {
                RuleRunner.RuleOutcome outcome = ruleRunner.run(e, rule);
                if (outcome.retryLater()) {
                    retryLater(e, retryReason(outcome));
                    return;
                }
            }
            // the published rules have run; a test waiting for this contact takes the event too
            finish(e, EventOutcome.DONE, null, tests.capture(e));
        } catch (RuntimeException ex) {
            String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            log.warn("automation event {} failed on attempt {}: {}", e.getId(), e.getAttempts(), message, ex);
            if (e.getAttempts() < props.automation().maxAttempts()) {
                release(e, new Update().set("lastError", message));
            } else {
                finish(e, EventOutcome.FAILED, message, List.of());
            }
        }
    }

    private void retryLater(AutomationEvent e, String reason) {
        release(e, new Update().set("notBefore", clock.instant().plus(props.automation().retryDelay()))
                .set("lastError", reason));
    }

    /** Puts the event back in the queue, if this claim still holds it. */
    private void release(AutomationEvent e, Update u) {
        mongo.updateFirst(held(e), u.set("status", EventStatus.PENDING).unset("leaseUntil"), AutomationEvent.class);
    }

    /**
     * Files the event in the history (once, whoever gets there first) and takes it off the queue. {@code ruleIds} are
     * the rules that ran live; {@code testIds} the tests that took the event.
     */
    private void finish(AutomationEvent e, EventOutcome outcome, String error, List<String> testIds) {
        Instant now = clock.instant();
        List<AutomationRun> eventRuns = runs.findByEventIdIn(List.of(e.getId()));
        AutomationEventRecord r = new AutomationEventRecord();
        r.setId(e.getId());
        r.setCustomerId(e.getCustomerId());
        r.setWorkspaceId(e.getWorkspaceId());
        r.setKind(e.getKind());
        r.setData(e.getData());
        r.setContactId(e.getContactId());
        r.setDealId(e.getData() == null || e.getData().deal() == null ? null : e.getData().deal().id());
        r.setOutcome(outcome);
        r.setError(error);
        r.setAttempts(e.getAttempts());
        r.setRuleIds(eventRuns.stream().filter(run -> !run.isTest()).map(AutomationRun::getRuleId).distinct()
                .toList());
        r.setTestIds(new ArrayList<>(testIds));
        r.setCreatedAt(e.getCreatedAt());
        r.setFinishedAt(now);
        try {
            mongo.insert(r);
        } catch (DuplicateKeyException already) {
            // an earlier claim filed it before it could take the event off the queue
        }
        // a run left RUNNING here belongs to a rule moved to draft or deleted while the event waited for a retry
        mongo.updateMulti(new Query(Criteria.where("eventId").is(e.getId()).and("status").is(RunStatus.RUNNING)),
                new Update().set("status", RunStatus.INTERRUPTED).set("finishedAt", now), AutomationRun.class);
        mongo.remove(held(e), AutomationEvent.class);
    }

    private static Query held(AutomationEvent e) {
        return new Query(Criteria.where("_id").is(e.getId()).and("attempts").is(e.getAttempts()));
    }

    private static String retryReason(RuleRunner.RuleOutcome outcome) {
        return outcome.run().map(AutomationRun::getSteps).orElse(List.of()).stream()
                .filter(s -> s != null && s.outcome() == StepOutcome.RETRYING).map(RunStep::reason).findFirst()
                .orElse("A step is waiting for a retry");
    }
}
