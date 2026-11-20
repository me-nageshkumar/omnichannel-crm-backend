package me.nageshk.omnichannel.meta.graph;

import java.time.Duration;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/** Meta's error replies to {@link GraphException}s (codes from Meta's Cloud API error reference). */
public final class GraphErrors {

    private static final Set<Integer> AUTH = Set.of(190, 102, 10, 200, 0);
    private static final Set<Integer> RATE = Set.of(4, 17, 32, 613, 80007, 130429, 131048, 131056);
    private static final int WINDOW = 131047;

    private GraphErrors() {}

    /** {@code body} may be null (no or unreadable body); {@code retryAfter} from the response header, if any. */
    public static GraphException of(int status, JsonNode body, Duration retryAfter) {
        JsonNode error = body == null ? null : body.get("error");
        Integer code = error != null && error.hasNonNull("code") ? error.get("code").asInt() : null;
        String title = text(error, "message");
        String details = error == null ? null : text(error.get("error_data"), "details");
        if (title == null) title = text(error, "error_user_msg");
        return new GraphException(kind(status, code), status, code, title, details, retryAfter);
    }

    private static GraphException.Kind kind(int status, Integer code) {
        if (code != null && code == WINDOW) return GraphException.Kind.WINDOW_CLOSED;
        if (status == 429 || (code != null && RATE.contains(code))) return GraphException.Kind.RATE_LIMIT;
        if (status == 401 || (code != null && code == 190)) return GraphException.Kind.AUTH;
        if (status >= 500) return GraphException.Kind.UNAVAILABLE;
        if (status == 404) return GraphException.Kind.NOT_FOUND;
        if (status == 403 && code != null && AUTH.contains(code)) return GraphException.Kind.AUTH;
        return GraphException.Kind.BAD_REQUEST;
    }

    private static String text(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field)) return null;
        String s = node.get(field).asString();
        return s == null || s.isBlank() ? null : s;
    }
}
