package me.nageshk.omnichannel.service.inbox;

import java.time.Duration;
import java.time.Instant;
import me.nageshk.omnichannel.db.inbox.WaWebChat;

/**
 * Meta's customer service window (spec 2026-11-20 §5.5): free-form messages on a Cloud API number only within 24 hours
 * of the customer's last message; templates any time. Computed, never stored.
 */
public final class ServiceWindow {

    public static final Duration LENGTH = Duration.ofHours(24);

    /** {@code closesAt} is null when the customer never wrote. */
    public record ChatWindow(boolean open, Instant closesAt) {}

    private ServiceWindow() {}

    public static ChatWindow of(WaWebChat chat, Instant now) {
        Instant last = chat == null ? null : chat.getLastInboundAt();
        if (last == null) return new ChatWindow(false, null);
        Instant closes = last.plus(LENGTH);
        return new ChatWindow(now.isBefore(closes), closes);
    }
}
