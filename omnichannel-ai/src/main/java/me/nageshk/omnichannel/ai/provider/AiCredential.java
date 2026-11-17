package me.nageshk.omnichannel.ai.provider;

import java.util.Map;

/**
 * The decrypted key and the connection's plain settings, held only for the length of a call; never logged.
 */
public record AiCredential(String apiKey, Map<String, String> settings) {

    public AiCredential {
        settings = settings == null ? Map.of() : Map.copyOf(settings);
    }

    public AiCredential(String apiKey) {
        this(apiKey, Map.of());
    }

    /** The setting's value, or null when it is unset or blank. */
    public String setting(String key) {
        String v = settings.get(key);
        return v == null || v.isBlank() ? null : v;
    }

    @Override
    public String toString() {
        return "AiCredential[apiKey=****]";
    }
}
