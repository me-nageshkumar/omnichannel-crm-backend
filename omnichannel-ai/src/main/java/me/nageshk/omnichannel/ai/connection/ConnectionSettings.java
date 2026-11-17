package me.nageshk.omnichannel.ai.connection;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import me.nageshk.omnichannel.ai.api.AiConnectionField;
import me.nageshk.omnichannel.common.net.HttpAddressGuard;

/**
 * Checks a connection's plain settings against the fields its provider declares. Settings are never secrets; they end
 * up in HTTP headers and URLs, so control characters and private addresses are refused here.
 */
public final class ConnectionSettings {

    static final int MAX_LENGTH = 500;

    private ConnectionSettings() {
    }

    /**
     * The settings to store, in the order the provider declares them; problems go to {@code errors} under
     * {@code settings.<key>}. Values are trimmed and a blank one is absent.
     */
    public static Map<String, String> clean(List<AiConnectionField> declared, Map<String, String> in,
            HttpAddressGuard guard, Map<String, String> errors) {
        Map<String, String> given = in == null ? Map.of() : in;
        for (String key : given.keySet()) {
            if (declared.stream().noneMatch(f -> f.key().equals(key))) {
                errors.put("settings." + key, "is not a setting of this provider");
            }
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (AiConnectionField f : declared) {
            String raw = given.get(f.key());
            String value = raw == null ? "" : raw.strip();
            if (value.isEmpty()) {
                if (f.required()) errors.put("settings." + f.key(), "is required");
                continue;
            }
            String problem = problem(f, value, guard);
            if (problem != null) errors.put("settings." + f.key(), problem);
            else out.put(f.key(), value);
        }
        return out;
    }

    private static String problem(AiConnectionField f, String value, HttpAddressGuard guard) {
        if (value.length() > MAX_LENGTH) return "must be at most " + MAX_LENGTH + " characters";
        if (value.chars().anyMatch(c -> c < 0x20 || c == 0x7f)) return "must not contain control characters";
        // values go into HTTP headers and URLs: anything past printable ASCII (a pasted zero-width space, an accent)
        // would be refused by the HTTP client at call time, long after the save
        if (value.chars().anyMatch(c -> c > 0x7e)) return "must be plain text without special or invisible characters";
        if (f.kind() != AiConnectionField.Kind.URL) return null;
        URI uri;
        try {
            uri = new URI(value);
        } catch (java.net.URISyntaxException e) {
            return "must be an https URL";
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) return "must be an https URL";
        try {
            guard.check(uri);
        } catch (IllegalArgumentException e) {
            return "must be a public address";
        }
        return null;
    }
}
