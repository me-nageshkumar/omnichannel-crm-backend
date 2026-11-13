package me.nageshk.omnichannel.service.automation;

import me.nageshk.omnichannel.db.automation.StepOutcome;

/**
 * How an action went, with the reason shown in the run's history; a Start chatbot step also keeps the chatbot and
 * the run it started, so the console can open it.
 */
public record ActionResult(StepOutcome outcome, String reason, String chatbotId, String chatbotRunId) {

    public ActionResult(StepOutcome outcome, String reason) {
        this(outcome, reason, null, null);
    }

    public static ActionResult done(String reason) {
        return new ActionResult(StepOutcome.DONE, reason);
    }

    /** A Start chatbot step whose run exists (started now, or found from an earlier attempt). */
    public static ActionResult started(String reason, String chatbotId, String chatbotRunId) {
        return new ActionResult(StepOutcome.DONE, reason, chatbotId, chatbotRunId);
    }

    public static ActionResult skipped(String reason) {
        return new ActionResult(StepOutcome.SKIPPED, reason);
    }

    public static ActionResult failed(String reason) {
        return new ActionResult(StepOutcome.FAILED, reason);
    }

    /**
     * The action may or may not have happened (a send the extension got but did not confirm): final, never retried,
     * so a message is never sent twice.
     */
    public static ActionResult interrupted(String reason) {
        return new ActionResult(StepOutcome.INTERRUPTED, reason);
    }

    /** A transient failure before anything left the server: the worker retries the step later. */
    public static ActionResult retryLater(String reason) {
        return new ActionResult(StepOutcome.RETRYING, reason);
    }
}
