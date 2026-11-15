package me.nageshk.omnichannel.ai.provider.openai;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import me.nageshk.omnichannel.ai.api.AiReplyFormat;
import me.nageshk.omnichannel.ai.provider.AiItem;
import me.nageshk.omnichannel.ai.provider.AiTool;
import me.nageshk.omnichannel.ai.provider.ProviderRequest;
import me.nageshk.omnichannel.ai.provider.ToolChoice;

/** Maps the neutral {@link ProviderRequest} to the body of OpenAI's {@code POST /responses}. Null and empty fields are omitted. */
public final class OpenAiRequests {

    static final String STATE_KEY = "responseId";

    private OpenAiRequests() {
    }

    public static Map<String, Object> body(ProviderRequest r) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("model", r.model());
        if (notBlank(r.instructions())) b.put("instructions", r.instructions());
        b.put("input", input(r.input()));
        Object prev = r.state() == null ? null : r.state().get(STATE_KEY);
        if (prev instanceof String s && !s.isBlank()) b.put("previous_response_id", s);
        b.put("store", true);
        if (r.tools() != null && !r.tools().isEmpty()) {
            List<Map<String, Object>> tools = new ArrayList<>();
            for (AiTool t : r.tools()) tools.add(tool(t));
            b.put("tools", tools);
        }
        if (r.toolChoice() != null) b.put("tool_choice", choice(r.toolChoice()));
        AiReplyFormat f = r.format();
        if (f != null && f.type() == AiReplyFormat.Type.JSON) {
            Map<String, Object> fmt = new LinkedHashMap<>();
            fmt.put("type", "json_schema");
            fmt.put("name", "reply");
            fmt.put("schema", f.schema());
            fmt.put("strict", true);
            b.put("text", Map.of("format", fmt));
        }
        if (notBlank(r.reasoningEffort())) b.put("reasoning", Map.of("effort", r.reasoningEffort()));
        if (r.maxOutputTokens() != null) b.put("max_output_tokens", r.maxOutputTokens());
        if (r.temperature() != null) b.put("temperature", r.temperature());
        if (r.metadata() != null && !r.metadata().isEmpty()) b.put("metadata", r.metadata());
        return b;
    }

    private static List<Map<String, Object>> input(List<AiItem> items) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (items == null) return out;
        for (AiItem item : items) {
            Map<String, Object> m = new LinkedHashMap<>();
            switch (item) {
                case AiItem.UserText u -> {
                    m.put("role", "user");
                    m.put("content", u.text());
                }
                case AiItem.AssistantText a -> {
                    m.put("role", "assistant");
                    m.put("content", a.text());
                }
                case AiItem.ToolCallItem c -> {
                    m.put("type", "function_call");
                    m.put("call_id", c.callId());
                    m.put("name", c.name());
                    m.put("arguments", c.argumentsJson());
                }
                case AiItem.ToolResultItem t -> {
                    m.put("type", "function_call_output");
                    m.put("call_id", t.callId());
                    m.put("output", t.output());
                }
            }
            out.add(m);
        }
        return out;
    }

    private static Map<String, Object> tool(AiTool t) {
        Map<String, Object> m = new LinkedHashMap<>();
        switch (t) {
            case AiTool.FunctionTool f -> {
                m.put("type", "function");
                m.put("name", f.name());
                if (notBlank(f.description())) m.put("description", f.description());
                m.put("strict", strictReady(f.parameters()));
                m.put("parameters", f.parameters());
            }
            case AiTool.HostedMcpTool h -> {
                m.put("type", "mcp");
                m.put("server_label", h.label());
                m.put("server_url", h.url());
                if (h.headers() != null && !h.headers().isEmpty()) m.put("headers", h.headers());
                if (h.allowedTools() != null && !h.allowedTools().isEmpty()) m.put("allowed_tools", h.allowedTools());
                m.put("require_approval", "never");
            }
            case AiTool.FileSearchTool fs -> {
                m.put("type", "file_search");
                m.put("vector_store_ids", fs.vectorStoreIds());
            }
            case AiTool.WebSearchTool w -> m.put("type", "web_search");
        }
        return m;
    }

    /**
     * Whether OpenAI's strict mode accepts the schema: every object closed ({@code additionalProperties: false}) with
     * all its properties required, all the way down. Our finish tool is; most MCP tool schemas are not, and strict
     * mode would refuse them.
     */
    static boolean strictReady(Object schema) {
        if (!(schema instanceof Map<?, ?> s)) return true;
        if (s.containsKey("$ref") || s.containsKey("oneOf") || s.containsKey("allOf") || s.containsKey("not")
                || s.containsKey("patternProperties")) {
            return false;
        }
        Object type = s.get("type");
        boolean object = "object".equals(type) || (type instanceof List<?> l && l.contains("object"))
                || s.containsKey("properties");
        if (object) {
            if (!Boolean.FALSE.equals(s.get("additionalProperties"))) return false;
            if (!(s.get("properties") instanceof Map<?, ?> props)) return false;
            if (!(s.get("required") instanceof List<?> required) || !required.containsAll(props.keySet())) return false;
            for (Object p : props.values()) {
                if (!strictReady(p)) return false;
            }
        }
        if (s.containsKey("items") && !strictReady(s.get("items"))) return false;
        if (s.get("anyOf") instanceof List<?> any) {
            for (Object a : any) {
                if (!strictReady(a)) return false;
            }
        }
        return true;
    }

    private static Object choice(ToolChoice c) {
        return switch (c.mode()) {
            case AUTO -> "auto";
            case REQUIRED -> "required";
            case NAMED -> Map.of("type", "function", "name", c.name());
        };
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
