package me.nageshk.omnichannel.service.chatbot.flow.settings;

import java.util.List;
import me.nageshk.omnichannel.ai.api.AiField;
import me.nageshk.omnichannel.ai.api.AiOutcome;
import me.nageshk.omnichannel.ai.api.AiOverrides;
import me.nageshk.omnichannel.ai.api.AiReplyFormat;
import me.nageshk.omnichannel.db.automation.ConditionNode;

/**
 * The AI node (spec §5.9): a brand profile, one call or a conversation, overrides of the profile, templated
 * instructions and input, outcomes and fields for the finish tool, the reply format, and the ways a conversation ends.
 */
public record AiNodeSettings(String profileId, Mode mode, AiOverrides overrides, String extraInstructions,
        String userMessage, String initialMessage, Boolean sendReply, List<AiOutcome> outcomes, List<AiField> collect,
        AiReplyFormat replyFormat, List<String> exitWords, ConditionNode exitCondition, Integer maxTurns,
        String continueFrom) {

    public static final int DEFAULT_MAX_TURNS = 20;

    public enum Mode { SINGLE, CONVERSATION }

    /** SINGLE when unset. */
    public Mode modeOrDefault() {
        return mode == null ? Mode.SINGLE : mode;
    }

    public List<AiOutcome> outcomesOrEmpty() {
        return outcomes == null ? List.of() : outcomes;
    }

    public List<AiField> collectOrEmpty() {
        return collect == null ? List.of() : collect;
    }

    public List<String> exitWordsOrEmpty() {
        return exitWords == null ? List.of() : exitWords;
    }

    /** A conversation always sends; a single call only when asked. */
    public boolean sends() {
        return modeOrDefault() == Mode.CONVERSATION || Boolean.TRUE.equals(sendReply);
    }

    public int maxTurnsOrDefault() {
        return maxTurns == null ? DEFAULT_MAX_TURNS : maxTurns;
    }
}
