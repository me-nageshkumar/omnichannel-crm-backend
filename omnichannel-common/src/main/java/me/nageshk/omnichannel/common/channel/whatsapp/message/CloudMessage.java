package me.nageshk.omnichannel.common.channel.whatsapp.message;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * A Cloud API {@code /messages} request, byte for byte Meta's shape. {@code messagingProduct} is
 * {@link MessagingProduct#WHATSAPP_WEB} when the channel is the WhatsApp Web bridge.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CloudMessage(String messagingProduct, String recipientType, String to, String type, Text text,
        Interactive interactive, Context context) {

    public static final String TEXT = "text";
    public static final String INTERACTIVE = "interactive";

    /** The message this one replies to. */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Context(String messageId) {}

    /** A copy with another {@code to}. */
    public CloudMessage withTo(String newTo) {
        return new CloudMessage(messagingProduct, recipientType, newTo, type, text, interactive, context);
    }
}
