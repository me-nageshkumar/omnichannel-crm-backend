package me.nageshk.omnichannel.contact;

import java.util.Locale;
import java.util.Set;

/** Derives the immutable, placeholder-safe key of a contact attribute from its name. */
public final class AttributeKeys {

    static final int MAX = 40;

    private AttributeKeys() {}

    /** Lowercase, non-alphanumeric runs to "_", trimmed, "a_" prefix if not letter-first, cut to 40, "_2".. on clash. */
    public static String generate(String name, Set<String> taken) {
        String k = (name == null ? "" : name).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        if (k.isEmpty()) k = "attribute";
        else if (!(k.charAt(0) >= 'a' && k.charAt(0) <= 'z')) k = "a_" + k;
        if (k.length() > MAX) k = k.substring(0, MAX);
        if (!taken.contains(k)) return k;
        for (int n = 2;; n++) {
            String suffix = "_" + n;
            String candidate = k.substring(0, Math.min(k.length(), MAX - suffix.length())) + suffix;
            if (!taken.contains(candidate)) return candidate;
        }
    }
}
