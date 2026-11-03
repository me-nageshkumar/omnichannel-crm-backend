package me.nageshk.omnichannel.service.automation;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import me.nageshk.omnichannel.common.config.OmnichannelProperties;
import me.nageshk.omnichannel.db.automation.AutomationAction;
import me.nageshk.omnichannel.db.automation.AutomationActionType;
import me.nageshk.omnichannel.db.automation.AutomationEvent;
import me.nageshk.omnichannel.db.automation.AutomationEventKind;
import me.nageshk.omnichannel.db.automation.AutomationRule;
import me.nageshk.omnichannel.db.automation.AutomationRun;
import me.nageshk.omnichannel.db.automation.AutomationRunRepository;
import me.nageshk.omnichannel.db.automation.EventData;
import me.nageshk.omnichannel.db.automation.RunStatus;
import me.nageshk.omnichannel.db.automation.RunStep;
import me.nageshk.omnichannel.db.automation.StepOutcome;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/**
 * Runs one published rule for one event, at most once: the run document is stored before the first action and every
 * step is recorded as it ends, so a claim after a crash resumes the run instead of starting it again, and never sends
 * a message twice.
 */
@Component
public class RuleRunner {

    static final String INTERRUPTED_SEND = "The app stopped while sending; it may have gone";

    /** The run the rule has for the event, if it matched; {@code retryLater} when a step waits for a retry. */
    public record RuleOutcome(Optional<AutomationRun> run, boolean retryLater) {

        static RuleOutcome none() {
            return new RuleOutcome(Optional.empty(), false);
        }
    }

    private final AutomationRunRepository runs;
    private final MongoTemplate mongo;
    private final ActionRunner actions;
    private final AutomationCrm crm;
    private final OmnichannelProperties props;
    private final Clock clock;

    RuleRunner(AutomationRunRepository runs, MongoTemplate mongo, ActionRunner actions, AutomationCrm crm,
            OmnichannelProperties props, Clock clock) {
        this.runs = runs;
        this.mongo = mongo;
        this.actions = actions;
        this.crm = crm;
        this.props = props;
        this.clock = clock;
    }

    public RuleOutcome run(AutomationEvent e, AutomationRule rule) {
        Optional<AutomationRun> existing = runs.findByEventIdAndRuleIdAndTestFalse(e.getId(), rule.getId());
        if (existing.isPresent()) {
            AutomationRun run = existing.get();
            return run.getStatus() == RunStatus.RUNNING ? resume(e, rule, run) : RuleOutcome.none();
        }
        Map<String, Object> values = AutomationFields.values(e.getData(), inCrmNow(e));
        if (!ConditionEvaluator.matches(rule.getConditions(), values)) return RuleOutcome.none();
        AutomationRun run = newRun(e, rule);
        try {
            run = runs.insert(run);
        } catch (DuplicateKeyException raced) {
            return RuleOutcome.none();
        }
        return runActions(e, rule, run, 0);
    }

    /** Message events re-read the CRM, so a rule sees what the rules before it did; a deal is in the CRM. */
    private boolean inCrmNow(AutomationEvent e) {
        if (e.getKind() == AutomationEventKind.DEAL_LANE_CHANGED) return true;
        EventData d = e.getData();
        String contactId = d.contact() == null ? null : d.contact().id();
        String numberId = d.number() == null ? null : d.number().id();
        return contactId != null && numberId != null && crm.inCrm(e.getWorkspaceId(), contactId, numberId);
    }

    private AutomationRun newRun(AutomationEvent e, AutomationRule rule) {
        EventData d = e.getData();
        AutomationRun run = new AutomationRun();
        run.setCustomerId(e.getCustomerId());
        run.setWorkspaceId(e.getWorkspaceId());
        run.setEventId(e.getId());
        run.setEventKind(e.getKind());
        run.setRuleId(rule.getId());
        run.setRuleName(rule.getName());
        run.setTest(false);
        run.setStatus(RunStatus.RUNNING);
        EventData.ContactData contact = d.contact();
        run.setContactId(contact != null && contact.id() != null ? contact.id() : e.getContactId());
        run.setContactName(contact == null ? null : contact.name());
        run.setContactPhone(contact != null && contact.phone() != null ? contact.phone() : e.getContactPhone());
        run.setDealId(d.deal() == null ? null : d.deal().id());
        run.setDealTitle(d.deal() == null ? null : d.deal().title());
        run.setSummary(EventSummaries.of(e.getKind(), d));
        run.setSteps(new ArrayList<>());
        run.setStartedAt(clock.instant());
        return run;
    }

    /**
     * Picks a RUNNING run up at its first action without a final step. A send with no step at all was in flight
     * when the app stopped: it may have gone, so it is marked INTERRUPTED and not sent again.
     */
    private RuleOutcome resume(AutomationEvent e, AutomationRule rule, AutomationRun run) {
        List<RunStep> steps = run.getSteps() == null ? new ArrayList<>() : new ArrayList<>(run.getSteps());
        run.setSteps(steps);
        List<AutomationAction> list = actionsOf(rule);
        int start = 0;
        while (start < list.size() && isFinal(stepAt(steps, start))) start++;
        if (start < list.size() && stepAt(steps, start) == null
                && list.get(start).type() == AutomationActionType.SEND_TEMPLATE) {
            record(run, new RunStep(start, list.get(start).type(), StepOutcome.INTERRUPTED, INTERRUPTED_SEND,
                    clock.instant()));
            start++;
        }
        return runActions(e, rule, run, start);
    }

    private RuleOutcome runActions(AutomationEvent e, AutomationRule rule, AutomationRun run, int from) {
        List<AutomationAction> list = actionsOf(rule);
        ActionContext ctx = new ActionContext(e.getWorkspaceId(), e.getCustomerId(), e.getKind(), e.getId(),
                e.getData(), rule.getId(), rule.getName(), false);
        for (int i = from; i < list.size(); i++) {
            AutomationAction a = list.get(i);
            if (a.type() == AutomationActionType.SEND_TEMPLATE && stepAt(run.getSteps(), i) != null) {
                // a send retried: clear its RETRYING step first, so a crash during this send reads as in flight
                clear(run, i);
            }
            ActionResult r = actions.run(ctx, a);
            if (r.outcome() == StepOutcome.RETRYING) {
                if (e.getAttempts() < props.automation().maxAttempts()) {
                    record(run, new RunStep(i, a.type(), StepOutcome.RETRYING, r.reason(), clock.instant()));
                    return new RuleOutcome(Optional.of(run), true);
                }
                r = ActionResult.failed(r.reason());
            }
            record(run, new RunStep(i, a.type(), r.outcome(), r.reason(), clock.instant()));
        }
        return new RuleOutcome(Optional.of(end(run)), false);
    }

    private AutomationRun end(AutomationRun run) {
        boolean failed = false;
        boolean interrupted = false;
        for (RunStep s : run.getSteps()) {
            if (s == null) continue;
            failed |= s.outcome() == StepOutcome.FAILED;
            interrupted |= s.outcome() == StepOutcome.INTERRUPTED;
        }
        run.setStatus(failed ? RunStatus.FAILED : interrupted ? RunStatus.INTERRUPTED : RunStatus.DONE);
        run.setFinishedAt(clock.instant());
        mongo.updateFirst(byId(run), new Update().set("status", run.getStatus()).set("finishedAt", run.getFinishedAt()),
                AutomationRun.class);
        return run;
    }

    private void record(AutomationRun run, RunStep step) {
        mongo.updateFirst(byId(run), new Update().set("steps." + step.index(), step), AutomationRun.class);
        List<RunStep> steps = run.getSteps();
        while (steps.size() <= step.index()) steps.add(null);
        steps.set(step.index(), step);
    }

    private void clear(AutomationRun run, int index) {
        mongo.updateFirst(byId(run), new Update().set("steps." + index, null), AutomationRun.class);
        run.getSteps().set(index, null);
    }

    private static Query byId(AutomationRun run) {
        return new Query(Criteria.where("_id").is(run.getId()));
    }

    private static List<AutomationAction> actionsOf(AutomationRule rule) {
        return rule.getActions() == null ? List.of() : rule.getActions();
    }

    private static RunStep stepAt(List<RunStep> steps, int index) {
        return steps == null || index >= steps.size() ? null : steps.get(index);
    }

    private static boolean isFinal(RunStep s) {
        return s != null && s.outcome() != StepOutcome.RETRYING;
    }
}
