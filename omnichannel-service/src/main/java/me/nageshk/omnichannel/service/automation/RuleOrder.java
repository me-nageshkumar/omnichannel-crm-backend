package me.nageshk.omnichannel.service.automation;

import java.time.Instant;
import java.util.Comparator;
import me.nageshk.omnichannel.db.automation.AutomationRule;

/** The order rules run in for one event: by priority (larger first, unset last), then oldest first. */
public final class RuleOrder {

    public static final Comparator<AutomationRule> COMPARATOR = Comparator
            .comparing(AutomationRule::getPriority, Comparator.nullsLast(Comparator.<Integer>reverseOrder()))
            .thenComparing(AutomationRule::getCreationTime, Comparator.nullsLast(Comparator.<Instant>naturalOrder()))
            .thenComparing(AutomationRule::getId, Comparator.nullsLast(Comparator.<String>naturalOrder()));

    private RuleOrder() {
    }
}
