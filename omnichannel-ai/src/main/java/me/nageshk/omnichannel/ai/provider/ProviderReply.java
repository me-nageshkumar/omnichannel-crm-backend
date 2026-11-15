package me.nageshk.omnichannel.ai.provider;

import java.util.List;
import java.util.Map;
import me.nageshk.omnichannel.ai.api.AiUsage;

/**
 * One provider round trip's answer. {@code incompleteReason} is null for a complete reply, else why the provider
 * stopped early (OpenAI: {@code max_output_tokens}, {@code content_filter}). {@code refused}: the text is the model's
 * refusal, which is not the JSON a JSON format asked for.
 */
public record ProviderReply(String text, List<ToolCall> toolCalls, Map<String, Object> state, AiUsage usage,
        String responseId, boolean usedHostedTools, String incompleteReason, boolean refused) {

    /** A complete reply. */
    public ProviderReply(String text, List<ToolCall> toolCalls, Map<String, Object> state, AiUsage usage,
            String responseId, boolean usedHostedTools) {
        this(text, toolCalls, state, usage, responseId, usedHostedTools, null, false);
    }
}
