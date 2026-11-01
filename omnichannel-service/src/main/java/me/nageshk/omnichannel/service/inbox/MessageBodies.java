package me.nageshk.omnichannel.service.inbox;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * What in a stored or received message body is text a person wrote. WhatsApp puts a base64 thumbnail in the
 * {@code body} of a message with media and the caption in {@code caption}, so such a body is not text.
 */
public final class MessageBodies {

    private static final Set<String> TEXT_TYPES = Set.of("chat", "text");
    /** How base64 of a JPEG, PNG and WebP starts: a long token in a caption is text, not a thumbnail. */
    private static final List<String> IMAGE_STARTS = List.of("/9j/", "iVBOR", "UklGR");
    private static final int MIN_THUMBNAIL_LENGTH = 64;
    private static final Pattern BASE64 = Pattern.compile("[A-Za-z0-9+/]+={0,2}");

    private MessageBodies() {
    }

    static boolean isText(String type) {
        return type == null || TEXT_TYPES.contains(type);
    }

    /** The body, or null when it is a thumbnail; what was typed in a text message is always kept. */
    public static String readable(String type, String body) {
        return !isText(type) && isThumbnail(body) ? null : body;
    }

    private static boolean isThumbnail(String body) {
        return body != null && body.length() >= MIN_THUMBNAIL_LENGTH && IMAGE_STARTS.stream().anyMatch(body::startsWith)
                && BASE64.matcher(body).matches();
    }
}
