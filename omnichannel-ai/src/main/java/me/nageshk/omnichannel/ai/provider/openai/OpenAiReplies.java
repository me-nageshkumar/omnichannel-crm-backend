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

    /**
     * A {@code failed} response throws (UNAVAILABLE for OpenAI's own trouble, RATE_LIMIT, else BAD_REQUEST); an
     * {@code incomplete} one carries its reason; a refusal is the text, so the customer reads the model's own wording.
     */
    public static ProviderReply parse(JsonNode root) {
        String id = root.path("id").asString(null);
        String status = root.path("status").asString("");
        if ("failed".equals(status)) throw failed(root.path("error").path("code").asString(""));
        StringBuilder text = new StringBuilder();
        List<ToolCall> calls = new ArrayList<>();
        boolean usedHosted = false;
        boolean refused = false;
        for (JsonNode item : root.path("output")) {
            switch (item.path("type").asString("")) {
                case "message" -> {
                    for (JsonNode part : item.path("content")) {
                        switch (part.path("type").asString("")) {
                            case "output_text" -> text.append(part.path("text").asString(""));
                            case "refusal" -> {
                                text.append(part.path("refusal").asString(""));
                                refused = true;
                            }
                            default -> {
                            }
                        }
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
        String incomplete = "incomplete".equals(status)
                ? root.path("incomplete_details").path("reason").asString("unknown") : null;
        return new ProviderReply(text.toString(), calls, state, usage, id, usedHosted, incomplete, refused);
    }

    /** Only the code goes into the message: the provider's wording stays out of the run. */
    private static AiProviderException failed(String code) {
        AiProviderException.Code c = switch (code) {
            case "server_error", "vector_store_timeout", "" -> AiProviderException.Code.UNAVAILABLE;
            case "rate_limit_exceeded" -> AiProviderException.Code.RATE_LIMIT;
            default -> AiProviderException.Code.BAD_REQUEST;
        };
        return new AiProviderException(c, "OpenAI could not finish the response (" + (code.isEmpty() ? "no code" : code)
                + ")");
    }
}
