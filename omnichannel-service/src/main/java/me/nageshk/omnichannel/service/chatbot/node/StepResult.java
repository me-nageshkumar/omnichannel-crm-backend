package me.nageshk.omnichannel.service.chatbot.node;

import java.util.Map;
import me.nageshk.omnichannel.db.chatbot.Waiting;

/**
 * What a node did (spec §5.2): go on through {@code output}, wait for a task, end the run, jump to another chatbot,
 * or fail. Whether an output has an edge is the runner's business.
 */
public record StepResult(Kind kind, String output, Map<String, Object> result, String summary, String error,
        Waiting waiting, String jumpToChatbotId) {

    public enum Kind { NEXT, WAIT, END, JUMP, FAIL }

    public static StepResult next(String output, Map<String, Object> result, String summary) {
        return new StepResult(Kind.NEXT, output, result, summary, null, null, null);
    }

    public static StepResult wait(Waiting waiting, String summary) {
        return new StepResult(Kind.WAIT, null, null, summary, null, waiting, null);
    }

    public static StepResult end(String summary) {
        return new StepResult(Kind.END, null, null, summary, null, null, null);
    }

    public static StepResult jump(String chatbotId, String summary) {
        return new StepResult(Kind.JUMP, null, null, summary, null, null, chatbotId);
    }

    public static StepResult fail(String error) {
        return new StepResult(Kind.FAIL, null, null, null, error, null, null);
    }
}
