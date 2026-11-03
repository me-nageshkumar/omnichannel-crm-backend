package me.nageshk.omnichannel.service.automation;

import java.time.Instant;
import me.nageshk.omnichannel.db.automation.AutomationActionType;
import me.nageshk.omnichannel.db.automation.StepOutcome;

/** One action of a run as the console shows it; the outcome is null until the action has run. */
public record StepView(int index, AutomationActionType type, String summary, StepOutcome outcome, String reason,
        Instant at) {
}
