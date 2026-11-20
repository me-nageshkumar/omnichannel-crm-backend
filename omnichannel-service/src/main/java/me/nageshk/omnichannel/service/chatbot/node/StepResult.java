package me.nageshk.omnichannel.service.chatbot.node;

import java.util.Map;
import me.nageshk.omnichannel.db.chatbot.Waiting;

/**
 * What a node did (spec §5.2): go on through {@code output}, wait for a task, end the run, jump to another chatbot,
 * hand the chat to people and end the run (spec 2026-11-18 §5.4), or fail. Whether an output has an edge is the
 * runner's business.
 */
public record StepResult(Kind kind, String output, Map<String, Object> result, String summary, String error,
        Waiting waiting, String jumpToChatbotId, Map<String, Object> progress) {

    public enum Kind { NEXT, WAIT, END, JUMP, HAND_OVER, FAIL }

    public static StepResult next(String output, Map<String, Object> result, String summary) {
        return new StepResult(Kind.NEXT, output, result, summary, null, null, null, null);
    }

    public static StepResult wait(Waiting waiting, String summary) {
        return new StepResult(Kind.WAIT, null, null, summary, null, waiting, null, null);
    }

    public static StepResult end(String summary) {
        return new StepResult(Kind.END, null, null, summary, null, null, null, null);
    }

    /** The chat is with people now; the run ends as HANDED_OVER with {@code result} under the node's name. */
    public static StepResult handOver(Map<String, Object> result, String summary) {
        return new StepResult(Kind.HAND_OVER, null, result, summary, null, null, null, null);
    }

    public static StepResult jump(String chatbotId, String summary) {
        return new StepResult(Kind.JUMP, null, null, summary, null, null, chatbotId, null);
    }

    public static StepResult fail(String error) {
        return new StepResult(Kind.FAIL, null, null, null, error, null, null, null);
    }

    /** A copy carrying what the conversation has collected so far; the step records it (null keeps what it had). */
    public StepResult withProgress(Map<String, Object> progress) {
        return new StepResult(kind, output, result, summary, error, waiting, jumpToChatbotId, progress);
    }
}
