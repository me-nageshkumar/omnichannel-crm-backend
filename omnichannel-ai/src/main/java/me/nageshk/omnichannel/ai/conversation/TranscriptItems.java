package me.nageshk.omnichannel.ai.conversation;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.nageshk.omnichannel.ai.provider.AiItem;

/**
 * The stored form of transcript items: {@code {kind: USER|ASSISTANT|TOOL_CALL|TOOL_RESULT, text?, callId?, name?,
 * arguments?, output?}}, so a conversation reads the same whatever provider wrote it.
 */
public final class TranscriptItems {

    private TranscriptItems() {
    }

    public static Map<String, Object> map(AiItem item) {
        Map<String, Object> m = new LinkedHashMap<>();
        switch (item) {
            case AiItem.UserText u -> {
                m.put("kind", "USER");
                m.put("text", u.text());
            }
            case AiItem.AssistantText a -> {
                m.put("kind", "ASSISTANT");
                m.put("text", a.text());
            }
            case AiItem.ToolCallItem c -> {
                m.put("kind", "TOOL_CALL");
                m.put("callId", c.callId());
                m.put("name", c.name());
                m.put("arguments", c.argumentsJson());
            }
            case AiItem.ToolResultItem t -> {
                m.put("kind", "TOOL_RESULT");
                m.put("callId", t.callId());
                m.put("output", t.output());
            }
        }
        return m;
    }

    public static List<Map<String, Object>> maps(List<AiItem> items) {
        return new ArrayList<>(items.stream().map(TranscriptItems::map).toList());
    }

    /** Unknown kinds are skipped. */
    public static List<AiItem> items(List<Map<String, Object>> maps) {
        List<AiItem> out = new ArrayList<>();
        if (maps == null) return out;
        for (Map<String, Object> m : maps) {
            AiItem item = item(m);
            if (item != null) out.add(item);
        }
        return out;
    }

    public static AiItem item(Map<String, Object> m) {
        return switch (String.valueOf(m.get("kind"))) {
            case "USER" -> new AiItem.UserText(str(m, "text"));
            case "ASSISTANT" -> new AiItem.AssistantText(str(m, "text"));
            case "TOOL_CALL" -> new AiItem.ToolCallItem(str(m, "callId"), str(m, "name"), str(m, "arguments"));
            case "TOOL_RESULT" -> new AiItem.ToolResultItem(str(m, "callId"), str(m, "output"));
            default -> null;
        };
    }

    /**
     * The items a provider can take as a whole history: a tool result whose call was cut off by the transcript cap is
     * left out, since providers refuse an answer to a call they never saw.
     */
    public static List<AiItem> sendable(List<AiItem> items) {
        Set<String> calls = new HashSet<>();
        List<AiItem> out = new ArrayList<>();
        for (AiItem i : items) {
            if (i instanceof AiItem.ToolCallItem c) calls.add(c.callId());
            if (i instanceof AiItem.ToolResultItem r && !calls.contains(r.callId())) continue;
            out.add(i);
        }
        return out;
    }

    private static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? null : v.toString();
    }
}
