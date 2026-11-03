package me.nageshk.omnichannel.service.automation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import me.nageshk.omnichannel.db.automation.AutomationAction;
import me.nageshk.omnichannel.db.automation.AutomationRule;
import me.nageshk.omnichannel.db.template.MessageTemplate;
import me.nageshk.omnichannel.db.template.MessageTemplateRepository;
import me.nageshk.omnichannel.db.template.PlaceholderKind;
import me.nageshk.omnichannel.db.template.TemplatePlaceholder;
import me.nageshk.omnichannel.db.template.TemplateStatus;
import me.nageshk.omnichannel.db.template.TemplateType;
import me.nageshk.omnichannel.db.tenant.TeamRepository;
import me.nageshk.omnichannel.db.user.User;
import me.nageshk.omnichannel.security.user.UserAdminService;
import org.springframework.stereotype.Component;

/** What a rule must be to go live: well shaped, and every template, agent, team, pipeline and lane it names usable. */
@Component
public class RulePublishValidator {

    private final MessageTemplateRepository templates;
    private final TeamRepository teams;
    private final UserAdminService userAdmin;
    private final AutomationCrm crm;

    public RulePublishValidator(MessageTemplateRepository templates, TeamRepository teams,
            UserAdminService userAdmin, AutomationCrm crm) {
        this.templates = templates;
        this.teams = teams;
        this.userAdmin = userAdmin;
        this.crm = crm;
    }

    /** Field key to one sentence; empty when the rule can be published. */
    public Map<String, String> problems(AutomationRule r) {
        Map<String, String> out = new LinkedHashMap<>(RuleShapeValidator.check(new RuleRequest(r.getName(),
                r.getDescription(), r.getEvent(), r.getPriority(), r.getConditions(), r.getActions(), null)));
        List<AutomationAction> actions = r.getActions() == null ? List.of() : r.getActions();
        if (actions.isEmpty()) {
            out.put("actions", "Add at least one action");
            return out;
        }
        String ws = r.getWorkspaceId();
        List<User> agents = null;
        AutomationCrm.CrmOptions options = null;
        for (int i = 0; i < actions.size(); i++) {
            AutomationAction a = actions.get(i);
            if (a == null || a.type() == null) continue;
            String at = "actions[" + i + "]";
            switch (a.type()) {
                case SEND_TEMPLATE -> template(a, at, ws, out);
                case ASSIGN_CHAT -> {
                    if (a.mode() == null) break;
                    switch (a.mode()) {
                        case AGENT -> {
                            if (blank(a.userId())) {
                                out.put(at + ".userId", "Choose an agent");
                            } else {
                                if (agents == null) agents = userAdmin.withAccessTo(r.getCustomerId(), ws);
                                String id = a.userId();
                                if (agents.stream().noneMatch(u -> u.isActive() && u.getId().equals(id))) {
                                    out.put(at + ".userId", "The agent is no longer in the brand");
                                }
                            }
                        }
                        case TEAM_QUEUE, TEAM_ROUND_ROBIN -> {
                            if (blank(a.teamId())) {
                                out.put(at + ".teamId", "Choose a team");
                            } else if (teams.findByIdAndWorkspaceId(a.teamId(), ws).isEmpty()) {
                                out.put(at + ".teamId", "The team no longer exists");
                            }
                        }
                    }
                }
                case ADD_TO_CRM -> {
                    if (options == null) options = crm.options(ws);
                    pipeline(a, at, options, out);
                }
            }
        }
        return out;
    }

    private void template(AutomationAction a, String at, String ws, Map<String, String> out) {
        if (blank(a.templateId())) {
            out.put(at + ".templateId", "Choose a template");
            return;
        }
        MessageTemplate t = templates.findByIdAndWorkspaceId(a.templateId(), ws).orElse(null);
        if (t == null) {
            out.put(at + ".templateId", "The template no longer exists");
        } else if (t.getStatus() == TemplateStatus.ARCHIVED) {
            out.put(at + ".templateId", "The template is archived");
        } else if (t.getType() == TemplateType.CONTACT_CARD) {
            out.put(at + ".templateId", "Contact card templates cannot be sent");
        } else {
            for (TemplatePlaceholder p : t.getPlaceholders()) {
                if (p.getKind() != PlaceholderKind.AGENT || !p.isRequired()) continue;
                String v = a.values() == null ? null : a.values().get(p.getName());
                if (blank(v)) {
                    out.put(at + ".values." + p.getName(),
                            (blank(p.getLabel()) ? p.getName() : p.getLabel()) + " needs a value");
                }
            }
        }
    }

    private static void pipeline(AutomationAction a, String at, AutomationCrm.CrmOptions options,
            Map<String, String> out) {
        if (!options.enabled()) {
            out.put(at + ".pipelineId", "CRM is off for this brand");
            return;
        }
        String id = blank(a.pipelineId()) ? options.defaultPipelineId() : a.pipelineId();
        if (id == null) {
            out.put(at + ".pipelineId", "The brand has no default pipeline");
            return;
        }
        var pipeline = options.pipelines().stream().filter(p -> p.id().equals(id)).findFirst().orElse(null);
        if (pipeline == null) {
            out.put(at + ".pipelineId", "The pipeline no longer exists");
        } else if (!pipeline.active()) {
            out.put(at + ".pipelineId", "The pipeline is not active");
        } else if (!blank(a.stageId()) && pipeline.lanes().stream().noneMatch(l -> l.id().equals(a.stageId()))) {
            out.put(at + ".stageId", "The lane is not in the pipeline");
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
