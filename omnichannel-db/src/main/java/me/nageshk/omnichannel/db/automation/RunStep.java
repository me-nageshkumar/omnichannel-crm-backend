package me.nageshk.omnichannel.db.automation;

import java.time.Instant;

/** What happened to one action of a run. */
public record RunStep(int index, AutomationActionType type, StepOutcome outcome, String reason, Instant at) {
}
