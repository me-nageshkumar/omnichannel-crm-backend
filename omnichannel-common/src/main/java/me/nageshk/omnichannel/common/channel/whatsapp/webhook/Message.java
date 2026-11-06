package me.nageshk.omnichannel.common.channel.whatsapp.webhook;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Message(
        String from, String fromUserId, String id, String timestamp, String type,
        Context context, Referral referral, List<MessageError> errors,
        Text text, Media image, Media video, Media audio, Media document, Media sticker,
        Location location, List<ContactCard> contacts, Button button, Interactive interactive,
        Order order, System system, PollCreation pollCreation, PollVote pollVote, CallLog callLog,
        Revoked revoked, BizContentPlaceholder bizContentPlaceholder,
        NewsletterFollowerInvite newsletterFollowerInvite, Album album, WaWeb waWeb) {

    /** A text message with every other field null. */
    public static Message text(
            String id, String from, String fromUserId, String timestamp, String body, WaWeb waWeb) {
        return new Message(
                from, fromUserId, id, timestamp, "text", null, null, null,
                new Text(body), null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, waWeb);
    }

    public Message withWaWeb(WaWeb waWeb) {
        return new Message(from, fromUserId, id, timestamp, type, context, referral, errors, text, image, video, audio,
                document, sticker, location, contacts, button, interactive, order, system, pollCreation, pollVote,
                callLog, revoked, bizContentPlaceholder, newsletterFollowerInvite, album, waWeb);
    }
}
