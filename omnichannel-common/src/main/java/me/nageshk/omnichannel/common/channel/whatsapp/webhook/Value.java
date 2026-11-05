package me.nageshk.omnichannel.common.channel.whatsapp.webhook;

import java.util.List;

import me.nageshk.omnichannel.common.channel.ChannelContext;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Value(
        String messagingProduct,
        Metadata metadata,
        List<Contact> contacts,
        List<Message> messages,
        List<MessageEcho> messageEchoes,
        List<Status> statuses,
        List<MessageError> errors) {

    /** A value with only {@code messagingProduct} and {@code metadata} set. */
    public static Value of(ChannelContext ctx) {
        return new Value(
                "whatsapp",
                new Metadata(ctx.displayPhoneNumber(), ctx.channelAccountId()),
                null, null, null, null, null);
    }

    public Value withContacts(List<Contact> contacts) {
        return new Value(messagingProduct, metadata, contacts, messages, messageEchoes, statuses, errors);
    }

    public Value withMessages(List<Message> messages) {
        return new Value(messagingProduct, metadata, contacts, messages, messageEchoes, statuses, errors);
    }

    public Value withMessageEchoes(List<MessageEcho> messageEchoes) {
        return new Value(messagingProduct, metadata, contacts, messages, messageEchoes, statuses, errors);
    }

    public Value withStatuses(List<Status> statuses) {
        return new Value(messagingProduct, metadata, contacts, messages, messageEchoes, statuses, errors);
    }
}
