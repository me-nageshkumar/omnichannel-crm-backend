package me.nageshk.omnichannel.service.automation;

import me.nageshk.omnichannel.db.automation.AutomationEventKind;
import me.nageshk.omnichannel.db.automation.AutomationRuleRepository;
import me.nageshk.omnichannel.db.automation.AutomationTestRepository;
import me.nageshk.omnichannel.db.automation.RuleStatus;
import me.nageshk.omnichannel.db.automation.TestStatus;
import org.springframework.stereotype.Component;

/** Decides whether an event is worth queueing: a brand without a published rule or a waiting test of it wants none. */
@Component
public class AutomationGate {

    private final AutomationRuleRepository rules;
    private final AutomationTestRepository tests;

    AutomationGate(AutomationRuleRepository rules, AutomationTestRepository tests) {
        this.rules = rules;
        this.tests = tests;
    }

    /**
     * A published rule of the event exists in the brand, or a waiting test of it targets this contact (by id, or by
     * phone when {@code phone} is not null). A draft under test so queues only its test contact's events.
     */
    public boolean wants(String workspaceId, AutomationEventKind kind, String contactId, String phone) {
        if (rules.existsByWorkspaceIdAndEventAndStatus(workspaceId, kind, RuleStatus.PUBLISHED)) return true;
        return tests.findByWorkspaceIdAndEventAndStatus(workspaceId, kind, TestStatus.WAITING).stream()
                .anyMatch(t -> (contactId != null && contactId.equals(t.getTargetContactId()))
                        || (phone != null && phone.equals(t.getTargetPhone())));
    }
}
