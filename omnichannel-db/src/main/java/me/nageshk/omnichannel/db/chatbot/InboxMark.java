package me.nageshk.omnichannel.db.chatbot;

import java.time.Instant;
import me.nageshk.omnichannel.db.inbox.WaWebMessage;

/**
 * The last customer message a run has dealt with, in inbox order ({@code creationTime}, then id): messages stored
 * after it are new to the run. Kept in inbox terms, not app-clock times, because {@code creationTime} is stamped by
 * the store's own clock.
 */
public record InboxMark(Instant creationTime, String messageId) {

    /** The mark of a stored message; null when it was never stored (no {@code creationTime}). */
    public static InboxMark of(WaWebMessage m) {
        return m == null || m.getCreationTime() == null ? null : new InboxMark(m.getCreationTime(), m.getId());
    }

    /** The later of the two marks; either may be null. */
    public static InboxMark later(InboxMark a, InboxMark b) {
        if (a == null) return b;
        if (b == null) return a;
        int c = a.creationTime().compareTo(b.creationTime());
        if (c == 0) c = String.valueOf(a.messageId()).compareTo(String.valueOf(b.messageId()));
        return c >= 0 ? a : b;
    }
}
