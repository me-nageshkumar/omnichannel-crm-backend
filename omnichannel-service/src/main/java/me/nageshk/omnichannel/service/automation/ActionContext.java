package me.nageshk.omnichannel.service.automation;

import me.nageshk.omnichannel.db.automation.AutomationEventKind;
import me.nageshk.omnichannel.db.automation.EventData;

/**
 * What an action runs for: the event's facts, the rule that fired and whether this is a live test; in a test,
 * {@code userId} is the tester.
 */
public record ActionContext(String workspaceId, String customerId, AutomationEventKind kind, String eventId,
        EventData data, String ruleId, String ruleName, boolean test, String userId) {

    public ActionContext(String workspaceId, String customerId, AutomationEventKind kind, String eventId, EventData data,
            String ruleId, String ruleName, boolean test) {
        this(workspaceId, customerId, kind, eventId, data, ruleId, ruleName, test, null);
    }
}
