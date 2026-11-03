package me.nageshk.omnichannel.service.automation;

import java.time.Instant;
import java.util.List;
import me.nageshk.omnichannel.db.automation.AutomationEventKind;
import me.nageshk.omnichannel.db.automation.ConditionResult;
import me.nageshk.omnichannel.db.automation.EventData;
import me.nageshk.omnichannel.db.automation.TestEndReason;
import me.nageshk.omnichannel.db.automation.TestStatus;

/** A test of a draft rule as the console reads it. */
public record TestView(String id, String ruleId, String ruleName, AutomationEventKind event, TestStatus status,
        TestEndReason endReason, AutomationRef startedBy, Target target, boolean treatAsNew, List<NumberRef> numbers,
        Instant waitingSince, Instant idleEndsAt, Captured captured, int conditionCount,
        List<ConditionResult> conditions, Boolean matched, List<StepView> actions, Integer nextAction,
        String runId) {

    /** Who the test waits for. */
    public record Target(String contactId, String name, String phone) {
    }

    /** A number of the brand. */
    public record NumberRef(String id, String number, String label) {
    }

    /** The event the test took, with the chat shown as new when the test treats it so. */
    public record Captured(String eventId, Instant at, EventData data, boolean treatedAsNew) {
    }
}
