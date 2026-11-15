package me.nageshk.omnichannel.ai.provider;

import java.util.List;
import java.util.Map;

public sealed interface AiTool {

    record FunctionTool(String name, String description, Map<String, Object> parameters) implements AiTool {}

    record HostedMcpTool(String label, String url, Map<String, String> headers, List<String> allowedTools)
            implements AiTool {}

    record FileSearchTool(List<String> vectorStoreIds) implements AiTool {}

    record WebSearchTool() implements AiTool {}
}
