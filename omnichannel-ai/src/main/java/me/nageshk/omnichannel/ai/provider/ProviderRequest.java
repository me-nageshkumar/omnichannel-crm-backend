package me.nageshk.omnichannel.ai.provider;

import java.util.List;
import java.util.Map;
import me.nageshk.omnichannel.ai.api.AiReplyFormat;

public record ProviderRequest(String model, String instructions, List<AiItem> input, Map<String, Object> state,
        List<AiTool> tools, ToolChoice toolChoice, AiReplyFormat format, String reasoningEffort,
        Integer maxOutputTokens, Double temperature, Map<String, String> metadata) {}
