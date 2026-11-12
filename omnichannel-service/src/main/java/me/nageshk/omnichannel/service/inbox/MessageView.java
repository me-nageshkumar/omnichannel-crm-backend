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
        MessagePageView messagePage) {

    /** {@code available} is true once the bytes are cached, so the console can show a thumbnail at once. */
    public record MediaView(String mimetype, String filename, Long size, boolean available) {
    }

    public record Location(Double latitude, Double longitude, String description) {
    }
}
