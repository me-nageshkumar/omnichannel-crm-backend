package me.nageshk.omnichannel.ai.turn;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import me.nageshk.omnichannel.ai.api.AiField;
import me.nageshk.omnichannel.ai.provider.AiTool;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The tool the model calls, beside its reply, to note the collect details the customer has given so far (spec
 * 2026-11-20 §6.3). Strict: every collect key is required, {@code null} when not given yet. Its calls are answered
 * {@code "noted"} and never fail the turn.
 */
public final class ProgressTool {

    public static final String NAME = "note_progress";

    /** The answer to every note_progress call. */
    static final String NOTED = "noted";

    private static final String DESCRIPTION = "Call this together with your reply whenever the customer has given one"
            + " of these details. Use null for anything not given yet. Never mention it to the customer.";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private ProgressTool() {
    }

    /** Empty when there is nothing to collect. */
    public static Optional<AiTool.FunctionTool> of(List<AiField> collect) {
        if (collect == null || collect.isEmpty()) return Optional.empty();
        Map<String, Object> props = new LinkedHashMap<>();
        List<String> keys = new ArrayList<>();
        for (AiField f : collect) {
            props.put(f.key(), FinishTool.fieldSchema(f));
            keys.add(f.key());
        }
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", keys);
        schema.put("additionalProperties", false);
        return Optional.of(new AiTool.FunctionTool(NAME, DESCRIPTION, schema));
    }

    /** The call's non-null values of the collect keys; anything else, or arguments that are not JSON, is left out. */
    public static Map<String, Object> parse(String argumentsJson, List<AiField> collect) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (argumentsJson == null || collect == null) return out;
        Map<String, Object> args;
        try {
            args = JSON.readValue(argumentsJson, new TypeReference<Map<String, Object>>() {});
        } catch (RuntimeException e) {
            return out;
        }
        if (args == null) return out;
        for (AiField f : collect) {
            Object v = args.get(f.key());
            if (v != null) out.put(f.key(), v);
        }
        return out;
    }
}
