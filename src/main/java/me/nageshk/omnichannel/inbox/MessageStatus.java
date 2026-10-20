package me.nageshk.omnichannel.inbox;

/** Delivery state of a message. */
public enum MessageStatus {
    PENDING(0), SENT(1), DELIVERED(2), READ(3), PLAYED(4), FAILED(-1);

    private final int rank;

    MessageStatus(int rank) {
        this.rank = rank;
    }

    /** Maps a whatsapp-web.js ack integer to a status; unknown values count as PENDING. */
    public static MessageStatus ofAck(int ack) {
        return switch (ack) {
            case -1 -> FAILED;
            case 1 -> SENT;
            case 2 -> DELIVERED;
            case 3 -> READ;
            case 4 -> PLAYED;
            default -> PENDING;
        };
    }

    /**
     * Rank order is PENDING &lt; SENT &lt; DELIVERED &lt; READ &lt; PLAYED. FAILED has no rank: it is reachable from any
     * state, so {@code FAILED.isAtLeast(x)} holds only for FAILED and PENDING, and nothing but FAILED is at least FAILED.
     */
    public boolean isAtLeast(MessageStatus other) {
        if (this == FAILED || other == FAILED) {
            return this == other || (this == FAILED && other == PENDING);
        }
        return rank >= other.rank;
    }
}
