package me.nageshk.omnichannel.service.automation;

import java.time.Instant;
import me.nageshk.omnichannel.db.automation.AutomationEventKind;
import me.nageshk.omnichannel.db.automation.RuleStatus;

/** A rule as the list shows it; the counts are of real runs, not tests. */
public record RuleSummaryView(String id, String name, AutomationEventKind event, RuleStatus status, Integer priority,
        String actionsSummary, Instant lastRunAt, long runs7d, long failures7d, Instant creationTime) {
}
