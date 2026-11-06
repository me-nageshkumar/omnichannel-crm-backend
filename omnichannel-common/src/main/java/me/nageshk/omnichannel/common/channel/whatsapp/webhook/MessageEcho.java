package me.nageshk.omnichannel.common.channel.whatsapp.webhook;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MessageEcho(
        String from, String to, String id, String timestamp, String type, Context context,
        Text text, Media image, Media video, Media audio, Media document, Media sticker,
        Location location, List<ContactCard> contacts, Button button, Interactive interactive,
        Order order, System system, PollCreation pollCreation, PollVote pollVote, CallLog callLog,
        Revoked revoked, BizContentPlaceholder bizContentPlaceholder,
        NewsletterFollowerInvite newsletterFollowerInvite, Album album, WaWeb waWeb) {

    public MessageEcho withWaWeb(WaWeb waWeb) {
        return new MessageEcho(from, to, id, timestamp, type, context, text, image, video, audio, document, sticker,
                location, contacts, button, interactive, order, system, pollCreation, pollVote, callLog, revoked,
                bizContentPlaceholder, newsletterFollowerInvite, album, waWeb);
    }
}
