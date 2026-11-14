package me.nageshk.omnichannel.service.automation;

import java.time.Instant;
import java.util.List;
import me.nageshk.omnichannel.db.automation.AutomationAction;
import me.nageshk.omnichannel.db.automation.AutomationEventKind;
import me.nageshk.omnichannel.db.automation.ConditionNode;
import me.nageshk.omnichannel.db.automation.RuleStatus;

/**
 * A rule as the list shows it; the counts are of real runs, not tests. {@code conditions} and {@code actions} are the
 * stored ones, for the list's hover summary (rule-hover-summary spec).
 */
public record RuleSummaryView(String id, String name, AutomationEventKind event, RuleStatus status, Integer priority,
        String actionsSummary, Instant lastRunAt, long runs7d, long failures7d, Instant creationTime,
        ConditionNode conditions, List<AutomationAction> actions) {
}
