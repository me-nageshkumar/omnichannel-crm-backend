package me.nageshk.omnichannel.crm.common;

import java.util.Map;

/**
 * The minimum checks on a record's {@code customFields} until phase B validates them against their definitions: keys
 * are stored as Mongo field names, so none may contain {@code $} or {@code .}, and each is at most
 * {@value #MAX_KEY} characters.
 */
public final class CustomFields {

    public static final int MAX_KEY = 40;

    private CustomFields() {}

    /** Reports the first bad key under {@code customFields}; null or empty maps pass. */
    public static void check(Map<String, Object> fields, Map<String, String> errors) {
        if (fields == null) return;
        for (String key : fields.keySet()) {
            if (key == null || key.isBlank() || key.contains("$") || key.contains(".") || key.length() > MAX_KEY) {
                errors.put("customFields", "keys must be 1 to " + MAX_KEY + " characters without '$' or '.'");
                return;
            }
        }
    }
}
