package me.nageshk.omnichannel.ai.provider;

import java.util.List;
import java.util.Map;
import me.nageshk.omnichannel.ai.api.AiUsage;

public record ProviderReply(String text, List<ToolCall> toolCalls, Map<String, Object> state, AiUsage usage,
        String responseId, boolean usedHostedTools) {}
