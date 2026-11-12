package me.nageshk.omnichannel.service.automation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import me.nageshk.omnichannel.db.automation.AutomationAction;
import me.nageshk.omnichannel.db.automation.AutomationRule;
import org.springframework.stereotype.Component;

/** What a rule must be to go live: well shaped, and every template, agent, team, pipeline and lane it names usable. */
@Component
public class RulePublishValidator {

    private final TemplateTargets templates;
    private final AssignTargets assign;
    private final AutomationCrm crm;

    public RulePublishValidator(TemplateTargets templates, AssignTargets assign, AutomationCrm crm) {
        this.templates = templates;
        this.assign = assign;
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
        AutomationCrm.CrmOptions options = null;
        for (int i = 0; i < actions.size(); i++) {
            AutomationAction a = actions.get(i);
            if (a == null || a.type() == null) continue;
            String at = "actions[" + i + "]";
            switch (a.type()) {
                case SEND_TEMPLATE -> templates.check(ws, a.templateId(), a.values())
                        .forEach((k, v) -> out.put(at + "." + k, v));
                case ASSIGN_CHAT -> {
                    if (a.mode() == null) break;
                    assign.check(ws, a.mode(), a.userId(), a.teamId()).forEach((k, v) -> out.put(at + "." + k, v));
                }
                case ADD_TO_CRM -> {
                    if (options == null) options = crm.options(ws);
                    CrmTargets.check(options, a.pipelineId(), a.stageId()).forEach((k, v) -> out.put(at + "." + k, v));
                }
            }
        }
        return out;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
