package me.nageshk.omnichannel.service.automation;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import me.nageshk.omnichannel.common.api.NotFoundException;
import me.nageshk.omnichannel.common.api.ValidationException;
import me.nageshk.omnichannel.db.automation.AutomationRule;
import me.nageshk.omnichannel.db.automation.AutomationRuleRepository;
import me.nageshk.omnichannel.db.automation.RuleStatus;
import me.nageshk.omnichannel.db.automation.TestEndReason;
import me.nageshk.omnichannel.db.user.User;
import me.nageshk.omnichannel.db.user.UserRepository;
import me.nageshk.omnichannel.security.auth.AuthenticatedUser;
import org.springframework.stereotype.Service;

/** The rules of a brand: list, edit as a draft, publish and delete. */
@Service
public class AutomationRuleService {

    private final AutomationRuleRepository rules;
    private final AutomationTestService tests;
    private final UserRepository users;
    private final RulePublishValidator publishValidator;
    private final AutomationViews views;
    private final Clock clock;

    public AutomationRuleService(AutomationRuleRepository rules, AutomationTestService tests,
            UserRepository users, RulePublishValidator publishValidator, AutomationViews views, Clock clock) {
        this.rules = rules;
        this.tests = tests;
        this.users = users;
        this.publishValidator = publishValidator;
        this.views = views;
        this.clock = clock;
    }

    /** The brand's rules in the order they run. */
    public List<RuleSummaryView> list(String workspaceId) {
        List<AutomationRule> all = new ArrayList<>(rules.findByWorkspaceId(workspaceId));
        all.sort(RuleOrder.COMPARATOR);
        return views.summaries(workspaceId, all);
    }

    public RuleView get(String workspaceId, String id) {
        return views.view(require(workspaceId, id));
    }

    public RuleView create(AuthenticatedUser me, String workspaceId, RuleRequest req) {
        checkShape(req);
        AutomationRule r = new AutomationRule();
        r.setCustomerId(me.customerId());
        r.setWorkspaceId(workspaceId);
        r.setStatus(RuleStatus.DRAFT);
        r.setCreatedByUserId(me.userId());
        r.setCreatedByName(nameOf(me));
        apply(r, req);
        return views.view(rules.save(r));
    }

    public RuleView update(AuthenticatedUser me, String workspaceId, String id, RuleRequest req) {
        AutomationRule r = require(workspaceId, id);
        if (r.getStatus() == RuleStatus.PUBLISHED) throw AutomationErrors.published();
        if (req.version() == null) throw new ValidationException("version", "is required");
        checkShape(req);
        apply(r, req);
        r.setVersion(req.version());
        return views.view(rules.save(r));
    }

    public RuleView publish(AuthenticatedUser me, String workspaceId, String id) {
        AutomationRule r = require(workspaceId, id);
        if (r.getStatus() == RuleStatus.PUBLISHED) return views.view(r);
        Map<String, String> problems = publishValidator.problems(r);
        if (!problems.isEmpty()) throw new ValidationException(problems);
        r.setStatus(RuleStatus.PUBLISHED);
        r.setPublishedAt(clock.instant());
        r.setPublishedByUserId(me.userId());
        r.setPublishedByName(nameOf(me));
        AutomationRule saved = rules.save(r);
        tests.endActive(saved.getId(), TestEndReason.RULE_PUBLISHED);
        return views.view(saved);
    }

    public RuleView unpublish(String workspaceId, String id) {
        AutomationRule r = require(workspaceId, id);
        if (r.getStatus() == RuleStatus.DRAFT) return views.view(r);
        r.setStatus(RuleStatus.DRAFT);
        r.setPublishedAt(null);
        r.setPublishedByUserId(null);
        r.setPublishedByName(null);
        return views.view(rules.save(r));
    }

    /** Runs and event history stay; only the rule goes. */
    public void delete(String workspaceId, String id) {
        AutomationRule r = require(workspaceId, id);
        if (r.getStatus() == RuleStatus.PUBLISHED) throw AutomationErrors.published();
        tests.endActive(r.getId(), TestEndReason.RULE_DELETED);
        rules.delete(r);
    }

    private AutomationRule require(String workspaceId, String id) {
        return rules.findByIdAndWorkspaceId(id, workspaceId).orElseThrow(() -> new NotFoundException("rule not found"));
    }

    private static void checkShape(RuleRequest req) {
        Map<String, String> errors = RuleShapeValidator.check(req);
        if (!errors.isEmpty()) throw new ValidationException(errors);
    }

    private static void apply(AutomationRule r, RuleRequest req) {
        r.setName(req.name().trim());
        r.setDescription(req.description());
        r.setEvent(req.event());
        r.setPriority(req.priority());
        r.setConditions(req.conditions());
        r.setActions(req.actions() == null ? new ArrayList<>() : new ArrayList<>(req.actions()));
    }

    private String nameOf(AuthenticatedUser me) {
        return users.findById(me.userId()).map(User::getName).orElse(me.getUsername());
    }
}
