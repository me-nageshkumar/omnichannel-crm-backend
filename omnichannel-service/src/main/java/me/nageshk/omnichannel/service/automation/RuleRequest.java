package me.nageshk.omnichannel.service.automation;

import java.util.List;
import me.nageshk.omnichannel.db.automation.AutomationAction;
import me.nageshk.omnichannel.db.automation.AutomationEventKind;
import me.nageshk.omnichannel.db.automation.ConditionNode;

/** A rule as a client submits it, before any check. */
public record RuleRequest(String name, String description, AutomationEventKind event, Integer priority,
        ConditionNode conditions, List<AutomationAction> actions, Long version) {
}
