package me.nageshk.omnichannel.service.automation;

import me.nageshk.omnichannel.db.automation.AutomationActionType;
import me.nageshk.omnichannel.db.automation.AutomationEventKind;
import me.nageshk.omnichannel.db.automation.AutomationRuleRepository;
import me.nageshk.omnichannel.db.automation.RuleStatus;
import me.nageshk.omnichannel.service.billing.WorkGate;
import me.nageshk.omnichannel.service.inbox.ChatbotStarts;
import org.springframework.stereotype.Component;

/** A published message rule with a Start chatbot action, under a plan that allows chatbots. */
@Component
class RuleChatbotStarts implements ChatbotStarts {

    private final AutomationRuleRepository rules;
    private final WorkGate workGate;

    RuleChatbotStarts(AutomationRuleRepository rules, WorkGate workGate) {
        this.rules = rules;
        this.workGate = workGate;
    }

    @Override
    public boolean mayStartFromMessage(String workspaceId) {
        if (!workGate.allows(workspaceId, WorkGate.CHATBOTS)) return false;
        return rules.findByWorkspaceIdAndEventAndStatus(workspaceId, AutomationEventKind.MESSAGE_RECEIVED,
                RuleStatus.PUBLISHED).stream().anyMatch(r -> r.getActions() != null && r.getActions().stream()
                        .anyMatch(a -> a != null && a.type() == AutomationActionType.START_CHATBOT));
    }
}
