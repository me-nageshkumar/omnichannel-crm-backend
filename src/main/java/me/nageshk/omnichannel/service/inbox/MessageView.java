package me.nageshk.omnichannel.service.inbox;

import java.time.Instant;
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
        String templateId) {

    /** {@code available} is true once the bytes are cached, so the console can show a thumbnail at once. */
    public record MediaView(String mimetype, String filename, Long size, boolean available) {
    }

    public record Location(Double latitude, Double longitude, String description) {
    }
}
