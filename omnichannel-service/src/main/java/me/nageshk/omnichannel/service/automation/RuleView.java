package me.nageshk.omnichannel.service.automation;

import java.time.Instant;
import java.util.List;
import me.nageshk.omnichannel.db.automation.AutomationAction;
import me.nageshk.omnichannel.db.automation.AutomationEventKind;
import me.nageshk.omnichannel.db.automation.ConditionNode;
import me.nageshk.omnichannel.db.automation.RuleStatus;
import me.nageshk.omnichannel.db.automation.TestStatus;

/** A rule as the console reads it. */
public record RuleView(String id, String name, String description, AutomationEventKind event, Integer priority,
        ConditionNode conditions, List<AutomationAction> actions, Long version, RuleStatus status,
        AutomationRef createdBy, Instant creationTime, Instant lastUpdateTime, Instant publishedAt,
        AutomationRef publishedBy, ActiveTest activeTest) {

    /** The rule's test in progress. */
    public record ActiveTest(String id, AutomationRef startedBy, TestStatus status) {
    }
}
