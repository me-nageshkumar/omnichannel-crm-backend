package me.nageshk.omnichannel.common.channel.whatsapp.message;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Set;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * A Cloud API {@code /messages} request, byte for byte Meta's shape. {@code messagingProduct} is
 * {@link MessagingProduct#WHATSAPP_WEB} when the channel is the WhatsApp Web bridge.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CloudMessage(String messagingProduct, String recipientType, String to, String type, Text text,
        Interactive interactive, MediaObject image, MediaObject video, MediaObject audio, MediaObject document,
        LocationObject location, Context context) {

    public static final String TEXT = "text";
    public static final String INTERACTIVE = "interactive";
    public static final String IMAGE = "image";
    public static final String VIDEO = "video";
    public static final String AUDIO = "audio";
    public static final String DOCUMENT = "document";
    public static final String LOCATION = "location";
    /** Every message type this project sends. */
    public static final Set<String> TYPES = Set.of(TEXT, INTERACTIVE, IMAGE, VIDEO, AUDIO, DOCUMENT, LOCATION);
    public static final Set<String> MEDIA_TYPES = Set.of(IMAGE, VIDEO, AUDIO, DOCUMENT);

    /** The message this one replies to. */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Context(String messageId) {}

    /** A text or interactive message (the shape before media and location types were added). */
    public CloudMessage(String messagingProduct, String recipientType, String to, String type, Text text,
            Interactive interactive, Context context) {
        this(messagingProduct, recipientType, to, type, text, interactive, null, null, null, null, null, context);
    }

    /** The media object matching {@code type}, or null when the type is not a media type. */
    public MediaObject media() {
        return switch (type == null ? "" : type) {
            case IMAGE -> image;
            case VIDEO -> video;
            case AUDIO -> audio;
            case DOCUMENT -> document;
            default -> null;
        };
    }

    /** A copy with another {@code to}. */
    public CloudMessage withTo(String newTo) {
        return new CloudMessage(messagingProduct, recipientType, newTo, type, text, interactive, image, video, audio,
                document, location, context);
    }
}
