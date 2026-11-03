package me.nageshk.omnichannel.service.automation;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import me.nageshk.omnichannel.db.automation.AutomationAction;
import me.nageshk.omnichannel.db.automation.AutomationActionType;
import me.nageshk.omnichannel.db.automation.AutomationRule;
import me.nageshk.omnichannel.db.automation.AutomationRun;
import me.nageshk.omnichannel.db.automation.AutomationRunRepository;
import me.nageshk.omnichannel.db.automation.AutomationTest;
import me.nageshk.omnichannel.db.automation.AutomationTestRepository;
import me.nageshk.omnichannel.db.automation.RunStatus;
import me.nageshk.omnichannel.db.automation.RunStep;
import me.nageshk.omnichannel.db.template.MessageTemplate;
import me.nageshk.omnichannel.db.template.MessageTemplateRepository;
import me.nageshk.omnichannel.db.tenant.Team;
import me.nageshk.omnichannel.db.tenant.TeamRepository;
import me.nageshk.omnichannel.db.user.User;
import me.nageshk.omnichannel.db.user.UserRepository;
import org.springframework.stereotype.Component;

/** Builds the rule views of the API. */
@Component
public class AutomationViews {

    private static final String SEPARATOR = " · ";

    private final AutomationTestRepository tests;
    private final AutomationRunRepository runs;
    private final MessageTemplateRepository templates;
    private final TeamRepository teams;
    private final UserRepository users;
    private final AutomationCrm crm;
    private final Clock clock;

    public AutomationViews(AutomationTestRepository tests, AutomationRunRepository runs,
            MessageTemplateRepository templates, TeamRepository teams, UserRepository users, AutomationCrm crm,
            Clock clock) {
        this.tests = tests;
        this.runs = runs;
        this.templates = templates;
        this.teams = teams;
        this.users = users;
        this.crm = crm;
        this.clock = clock;
    }

    public RuleView view(AutomationRule r) {
        RuleView.ActiveTest active = tests.findByRuleIdAndActiveTrue(r.getId()).map(AutomationViews::active)
                .orElse(null);
        return new RuleView(r.getId(), r.getName(), r.getDescription(), r.getEvent(), r.getPriority(),
                r.getConditions(), r.getActions() == null ? List.of() : r.getActions(), r.getVersion(), r.getStatus(),
                ref(r.getCreatedByUserId(), r.getCreatedByName()), r.getCreationTime(), r.getLastUpdateTime(),
                r.getPublishedAt(), ref(r.getPublishedByUserId(), r.getPublishedByName()), active);
    }

    /** One row per rule, in the order given. */
    public List<RuleSummaryView> summaries(String workspaceId, List<AutomationRule> rules) {
        Instant from = clock.instant().minus(Duration.ofDays(7));
        Map<String, List<AutomationRun>> recent = new HashMap<>();
        for (AutomationRun run : runs.findByWorkspaceIdAndTestFalseAndStartedAtGreaterThanEqual(workspaceId, from)) {
            recent.computeIfAbsent(run.getRuleId(), k -> new ArrayList<>()).add(run);
        }
        Lookups lookups = new Lookups(workspaceId);
        List<RuleSummaryView> out = new ArrayList<>();
        for (AutomationRule r : rules) {
            List<AutomationRun> mine = recent.getOrDefault(r.getId(), List.of());
            Instant last = runs.findFirstByRuleIdAndTestFalseOrderByStartedAtDesc(r.getId())
                    .map(AutomationRun::getStartedAt).orElse(null);
            out.add(new RuleSummaryView(r.getId(), r.getName(), r.getEvent(), r.getStatus(), r.getPriority(),
                    summary(r, lookups), last, mine.size(),
                    mine.stream().filter(x -> x.getStatus() == RunStatus.FAILED).count(), r.getCreationTime()));
        }
        return out;
    }

    private static RuleView.ActiveTest active(AutomationTest t) {
        return new RuleView.ActiveTest(t.getId(), new AutomationRef(t.getStartedByUserId(), t.getStartedByName()),
                t.getStatus());
    }

    private static AutomationRef ref(String id, String name) {
        return id == null ? null : new AutomationRef(id, name);
    }

    private String summary(AutomationRule r, Lookups l) {
        List<String> parts = new ArrayList<>();
        for (AutomationAction a : r.getActions() == null ? List.<AutomationAction>of() : r.getActions()) {
            String part = part(a, l);
            if (part != null) parts.add(part);
        }
        return String.join(SEPARATOR, parts);
    }

    /**
     * One summary per action, in order, as in the rule's {@code actionsSummary}; an action too incomplete to sum up
     * shows its type ("Send template", "Assign chat", "Add to CRM").
     */
    public List<String> actionSummaries(String workspaceId, List<AutomationAction> actions) {
        Lookups lookups = new Lookups(workspaceId);
        List<String> out = new ArrayList<>();
        for (AutomationAction a : actions == null ? List.<AutomationAction>of() : actions) {
            String part = part(a, lookups);
            out.add(part != null ? part : typeLabel(a == null ? null : a.type()));
        }
        return out;
    }

    /** The label a step was recorded with, or its type's name for a step stored without one. */
    public static String stepSummary(RunStep s) {
        return s.summary() != null ? s.summary() : typeLabel(s.type());
    }

    /** The action's name in the console. */
    public static String typeLabel(AutomationActionType type) {
        if (type == null) return "Unknown action";
        return switch (type) {
            case SEND_TEMPLATE -> "Send template";
            case ASSIGN_CHAT -> "Assign chat";
            case ADD_TO_CRM -> "Add to CRM";
        };
    }

    private static String part(AutomationAction a, Lookups l) {
        if (a == null || a.type() == null) return null;
        return switch (a.type()) {
            case SEND_TEMPLATE -> "Send “" + l.template(a.templateId()) + "”";
            case ASSIGN_CHAT -> a.mode() == null ? null : switch (a.mode()) {
                case AGENT -> "Assign to " + l.agent(a.userId());
                case TEAM_QUEUE -> "Queue in " + l.team(a.teamId());
                case TEAM_ROUND_ROBIN -> "Round robin in " + l.team(a.teamId());
            };
            case ADD_TO_CRM -> "Add to CRM (" + l.pipeline(a.pipelineId(), a.stageId()) + ")";
        };
    }

    /** Names of what actions refer to, each looked up once; a missing referent shows its id. */
    private final class Lookups {
        private final String workspaceId;
        private final Map<String, String> templateNames = new HashMap<>();
        private final Map<String, String> agentNames = new HashMap<>();
        private final Map<String, String> teamNames = new HashMap<>();
        private AutomationCrm.CrmOptions options;

        Lookups(String workspaceId) {
            this.workspaceId = workspaceId;
        }

        String template(String id) {
            return name(templateNames, id, i -> templates.findByIdAndWorkspaceId(i, workspaceId)
                    .map(MessageTemplate::getName));
        }

        String agent(String id) {
            return name(agentNames, id, i -> users.findById(i).map(User::getName));
        }

        String team(String id) {
            return name(teamNames, id, i -> teams.findByIdAndWorkspaceId(i, workspaceId).map(Team::getName));
        }

        String pipeline(String pipelineId, String laneId) {
            if (pipelineId == null || pipelineId.isBlank()) return "default pipeline";
            if (options == null) options = crm.options(workspaceId);
            var pipeline = options.pipelines().stream().filter(p -> p.id().equals(pipelineId)).findFirst();
            String name = pipeline.map(AutomationCrm.PipelineOption::name).orElse(pipelineId);
            if (laneId == null || laneId.isBlank()) return name;
            String lane = pipeline.flatMap(p -> p.lanes().stream().filter(x -> x.id().equals(laneId)).findFirst())
                    .map(AutomationCrm.LaneOption::name).orElse(laneId);
            return name + " › " + lane;
        }

        private String name(Map<String, String> cache, String id, Function<String, Optional<String>> find) {
            if (id == null) return "?";
            return cache.computeIfAbsent(id, i -> find.apply(i).orElse(i));
        }
    }
}
