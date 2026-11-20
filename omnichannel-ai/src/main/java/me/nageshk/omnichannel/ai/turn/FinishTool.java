package me.nageshk.omnichannel.ai.turn;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import me.nageshk.omnichannel.ai.api.AiField;
import me.nageshk.omnichannel.ai.api.AiFieldType;
import me.nageshk.omnichannel.ai.api.AiFinish;
import me.nageshk.omnichannel.ai.api.AiOutcome;
import me.nageshk.omnichannel.ai.provider.AiTool;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The tool the model calls to end a conversation with one of the node's outcomes. Strict: every property is required
 * (unknown values are null), as OpenAI's strict mode wants.
 */
public final class FinishTool {

    public static final String NAME = "finish";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private FinishTool() {
    }

    /** Empty when the node declares no outcomes. */
    public static Optional<AiTool.FunctionTool> of(List<AiOutcome> outcomes, List<AiField> collect) {
        if (outcomes == null || outcomes.isEmpty()) return Optional.empty();
        List<AiField> fields = collect == null ? List.of() : collect;

        Map<String, Object> fieldProps = new LinkedHashMap<>();
        List<String> keys = new ArrayList<>();
        for (AiField f : fields) {
            fieldProps.put(f.key(), fieldSchema(f));
            keys.add(f.key());
        }
        Map<String, Object> fieldsSchema = new LinkedHashMap<>();
        fieldsSchema.put("type", "object");
        fieldsSchema.put("properties", fieldProps);
        fieldsSchema.put("required", keys);
        fieldsSchema.put("additionalProperties", false);

        Map<String, Object> outcome = new LinkedHashMap<>();
        outcome.put("type", "string");
        outcome.put("enum", outcomes.stream().map(AiOutcome::id).toList());

        Map<String, Object> props = new LinkedHashMap<>();
        props.put("outcome", outcome);
        props.put("fields", fieldsSchema);
        props.put("reply", Map.of("type", List.of("string", "null"), "description",
                "The last message the customer gets: a closing message, never a question. Null for none."));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("outcome", "fields", "reply"));
        schema.put("additionalProperties", false);
        return Optional.of(new AiTool.FunctionTool(NAME, description(outcomes), schema));
    }

    /** One collect key as a strict property: its type or null, with its description when it has one. */
    static Map<String, Object> fieldSchema(AiField f) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", List.of(switch (f.type() == null ? AiFieldType.TEXT : f.type()) {
            case TEXT -> "string";
            case NUMBER -> "number";
            case BOOLEAN -> "boolean";
        }, "null"));
        if (f.description() != null && !f.description().isBlank()) p.put("description", f.description());
        return p;
    }

    /**
     * The finish call's arguments, fields cut to the declared collect keys; throws IllegalArgumentException when they
     * are not JSON or name no declared outcome.
     */
    public static AiFinish parse(String argumentsJson, List<AiOutcome> outcomes, List<AiField> collect) {
        Map<String, Object> args;
        try {
            args = JSON.readValue(argumentsJson == null ? "" : argumentsJson, new TypeReference<Map<String, Object>>() {});
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("The finish call's arguments are not JSON");
        }
        Object outcome = args.get("outcome");
        boolean known = outcome instanceof String s && outcomes != null
                && outcomes.stream().anyMatch(o -> o.id().equals(s));
        if (!known) throw new IllegalArgumentException("The finish call names no outcome of this node: " + outcome);
        Map<String, Object> fields = new LinkedHashMap<>();
        if (args.get("fields") instanceof Map<?, ?> m && collect != null) {
            for (AiField f : collect) {
                if (m.containsKey(f.key())) fields.put(f.key(), m.get(f.key()));
            }
        }
        Object reply = args.get("reply");
        return new AiFinish((String) outcome, fields, reply instanceof String s && !s.isBlank() ? s : null);
    }

    private static String description(List<AiOutcome> outcomes) {
        // Models tend to end with a question here ("please confirm") and the flow moves on without the answer: say no.
        StringBuilder b = new StringBuilder("Ends the conversation with an outcome. Call it only when the conversation is"
                + " over: never while you still need an answer or a confirmation from the customer (then reply normally"
                + " and wait for it). The outcomes:");
        for (AiOutcome o : outcomes) {
            b.append("\n- ").append(o.id());
            if (o.label() != null && !o.label().isBlank()) b.append(" (").append(o.label()).append(')');
            if (o.description() != null && !o.description().isBlank()) b.append(": ").append(o.description());
        }
        b.append("\nPut the details you learned in fields (null when unknown). reply is the last message the customer")
                .append(" gets: a closing message, never a question (null for none).");
        return b.toString();
    }
}
