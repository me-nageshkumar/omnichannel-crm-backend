package me.nageshk.omnichannel.db.automation;

import java.time.Instant;

/**
 * What happened to one action of a run. {@code summary} is the action's label when the step was recorded ("Send
 * “Welcome”"), so the run log keeps it after the rule is edited; steps stored before it was kept have none.
 */
public record RunStep(int index, AutomationActionType type, String summary, StepOutcome outcome, String reason,
        Instant at, String chatbotId, String chatbotRunId) {

    public RunStep(int index, AutomationActionType type, String summary, StepOutcome outcome, String reason, Instant at) {
        this(index, type, summary, outcome, reason, at, null, null);
    }
}
