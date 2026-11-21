package me.nageshk.omnichannel.service.inbox;

import me.nageshk.omnichannel.service.page.MessagePageView;
import java.time.Instant;
import java.util.List;
import me.nageshk.omnichannel.db.inbox.MessageDirection;
import me.nageshk.omnichannel.db.inbox.MessageSource;
import me.nageshk.omnichannel.db.inbox.MessageStatus;
import me.nageshk.omnichannel.db.inbox.WaWebMessage;

/** A message as the console sees it. */
public record MessageView(
        String id,
        String chatId,
        String waMessageId,
        MessageDirection direction,
        MessageSource source,
        String type,
        String body,
        MediaView media,
        Location location,
        Instant timestamp,
        MessageStatus status,
        WaWebMessage.StatusTimes statusTimes,
        ChatView.Ref sentBy,
        String templateId,
        List<String> pollOptions,
        List<WaWebMessage.PollVote> pollVotes,
        WaWebMessage.Call call,
        String businessTag,
        AdClickView adClick,
        /** The message page this message links to, else null. */
        String messagePageId,
        /** What the customer did on that page, else null. */
        MessagePageView messagePage,
        /** Why Meta could not deliver it, else null (Cloud numbers). */
        WaWebMessage.Failure failure,
        /** The Meta template it sent, as the customer saw it, else null. */
        me.nageshk.omnichannel.common.channel.whatsapp.message.TemplateSnapshot template,
        /** What it quotes, else null. */
        QuotedView quoted,
        /** True when WhatsApp marks it forwarded, else null. */
        Boolean forwarded) {

    /** A quoted message as the bubble shows it; {@code messageId} null when we do not have the original. */
    public record QuotedView(String messageId, boolean fromMe, String from, String title, String text, String type) {
        public static QuotedView of(WaWebMessage.Quoted q) {
            return q == null ? null : new QuotedView(q.messageId(), q.fromMe(), q.from(), q.title(), q.text(), q.type());
        }
    }

    /** A message without a delivery failure. */
    public MessageView(String id, String chatId, String waMessageId, MessageDirection direction, MessageSource source,
            String type, String body, MediaView media, Location location, Instant timestamp, MessageStatus status,
            WaWebMessage.StatusTimes statusTimes, ChatView.Ref sentBy, String templateId, List<String> pollOptions,
            List<WaWebMessage.PollVote> pollVotes, WaWebMessage.Call call, String businessTag, AdClickView adClick,
            String messagePageId, MessagePageView messagePage) {
        this(id, chatId, waMessageId, direction, source, type, body, media, location, timestamp, status, statusTimes,
                sentBy, templateId, pollOptions, pollVotes, call, businessTag, adClick, messagePageId, messagePage, null,
                null, null, null);
    }

    /** {@code available} is true once the bytes are cached, so the console can show a thumbnail at once. */
    public record MediaView(String mimetype, String filename, Long size, boolean available) {
    }

    public record Location(Double latitude, Double longitude, String description) {
    }
}
