package me.nageshk.omnichannel.ai.provider.openai;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import me.nageshk.omnichannel.ai.api.AiUsage;
import me.nageshk.omnichannel.ai.provider.AiProviderException;
import me.nageshk.omnichannel.ai.provider.ProviderReply;
import me.nageshk.omnichannel.ai.provider.ToolCall;
import tools.jackson.databind.JsonNode;

/** Maps an OpenAI Responses reply to the neutral {@link ProviderReply}. */
public final class OpenAiReplies {

    private OpenAiReplies() {
    }

    public static ProviderReply parse(JsonNode root) {
        String id = root.path("id").asString(null);
        StringBuilder text = new StringBuilder();
        List<ToolCall> calls = new ArrayList<>();
        boolean usedHosted = false;
        for (JsonNode item : root.path("output")) {
            switch (item.path("type").asString("")) {
                case "message" -> {
                    for (JsonNode part : item.path("content")) {
                        if ("output_text".equals(part.path("type").asString(""))) text.append(part.path("text").asString(""));
                    }
                }
                case "function_call" -> calls.add(new ToolCall(item.path("call_id").asString(null),
                        item.path("name").asString(null), item.path("arguments").asString("{}")));
                case "mcp_call" -> usedHosted = true;
                case "mcp_approval_request" -> throw new AiProviderException(AiProviderException.Code.APPROVAL_REQUIRED,
                        "The MCP server asked for approval of a tool call, which this setup cannot give.");
                default -> {
                }
            }
        }
        JsonNode u = root.path("usage");
        AiUsage usage = new AiUsage(u.path("input_tokens").asLong(0), u.path("output_tokens").asLong(0),
                u.path("input_tokens_details").path("cached_tokens").asLong(0),
                u.path("output_tokens_details").path("reasoning_tokens").asLong(0));
        Map<String, Object> state = id == null ? Map.of() : Map.of(OpenAiRequests.STATE_KEY, id);
        return new ProviderReply(text.toString(), calls, state, usage, id, usedHosted);
    }
}
