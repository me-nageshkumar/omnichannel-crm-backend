package me.nageshk.omnichannel.service.page;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.regex.Pattern;
import me.nageshk.omnichannel.common.api.ValidationException;

/**
 * A link address as a person types it, made clean (spec 2026-11-18 link addresses §5.1): {@code https://<host>[/<folder>]},
 * host lower-case, no trailing slash. {@code allowInsecure} (tests and local runs) also takes {@code http}, a port and a
 * host without a dot.
 */
public record PagesUrl(String baseUrl, String host, String path) {

    private static final String FIELD = "baseUrl";
    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9_-]+");
    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");
    private static final int MAX_SEGMENTS = 3;

    public static PagesUrl parse(String text, boolean allowInsecure) {
        if (text == null || text.isBlank()) throw refused("Enter an address");
        String t = text.strip();
        String lower = t.toLowerCase(Locale.ROOT);
        boolean https = lower.startsWith("https://");
        boolean http = lower.startsWith("http://");
        if (!https && !http) throw refused("Start the address with https://");
        if (http && !allowInsecure) throw refused("Use https://");
        String rest = t.substring(https ? 8 : 7);
        int slash = rest.indexOf('/');
        String authority = slash < 0 ? rest : rest.substring(0, slash);
        String rawPath = slash < 0 ? "" : rest.substring(slash);
        if (authority.contains("@") || rawPath.contains("?") || rawPath.contains("#") || authority.contains("?")
                || authority.contains("#")) {
            throw refused("Only a domain and an optional folder, e.g. https://example.com/wa");
        }
        if (authority.startsWith("[")) throw refused("Use a domain name, not an IP address");
        String host = authority;
        String port = null;
        int colon = authority.lastIndexOf(':');
        if (colon >= 0) {
            host = authority.substring(0, colon);
            port = authority.substring(colon + 1);
        }
        // "example.com." is the same name; browsers send the dot when it is typed
        host = host.toLowerCase(Locale.ROOT).replaceAll("\\.+$", "");
        if (host.isEmpty()) throw refused("Enter an address");
        if (!host.chars().allMatch(c -> c < 128)) throw refused("Use the ASCII (xn--) form of the name");
        if (IPV4.matcher(host).matches()) throw refused("Use a domain name, not an IP address");
        if (port != null && !allowInsecure) throw refused("Leave out the port");
        if (port != null && !port.matches("\\d{1,5}")) throw refused("Leave out the port");
        if (!host.matches("[a-z0-9-]+(\\.[a-z0-9-]+)*")) throw refused("Use a full domain name");
        if (!host.contains(".") && !allowInsecure) throw refused("Use a full domain name");

        String path = rawPath.endsWith("/") ? rawPath.substring(0, rawPath.length() - 1) : rawPath;
        if (!path.isEmpty()) {
            String[] parts = path.substring(1).split("/", -1);
            if (parts.length > MAX_SEGMENTS) throw refused("The folder may have up to 3 parts of letters, digits, - and _");
            for (String p : parts) {
                if (!SEGMENT.matcher(p).matches()) {
                    throw refused("The folder may have up to 3 parts of letters, digits, - and _");
                }
            }
        }
        String scheme = https ? "https" : "http";
        String base = scheme + "://" + host + (port == null ? "" : ":" + port) + path;
        try {
            new URI(base);
        } catch (URISyntaxException e) {
            throw refused("Only a domain and an optional folder, e.g. https://example.com/wa");
        }
        return new PagesUrl(base, host, path);
    }

    private static ValidationException refused(String message) {
        return new ValidationException(FIELD, message);
    }
}
