package me.nageshk.omnichannel.api;

import java.util.Map;

/** A request that fails a rule bean validation cannot express: 400 {@code VALIDATION} with a per-field map. */
public class ValidationException extends RuntimeException {

    public static final String CODE = "VALIDATION";

    private final Map<String, String> fields;

    public ValidationException(Map<String, String> fields) {
        super(fields.entrySet().stream().map(e -> e.getKey() + " " + e.getValue())
                .reduce((a, b) -> a + "; " + b).orElse("invalid request"));
        this.fields = Map.copyOf(fields);
    }

    public ValidationException(String field, String message) {
        this(Map.of(field, message));
    }

    public Map<String, String> fields() { return fields; }
}
