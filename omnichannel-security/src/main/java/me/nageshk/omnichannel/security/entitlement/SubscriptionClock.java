package me.nageshk.omnichannel.security.entitlement;

import java.time.Instant;
import java.time.ZoneId;
import me.nageshk.omnichannel.common.billing.SubscriptionState;
import me.nageshk.omnichannel.db.billing.Subscription;
import me.nageshk.omnichannel.db.billing.TermSegment;

/** Reads a subscription's dates: which term is in force and what state the customer is in. */
public final class SubscriptionClock {

    private SubscriptionClock() {}

    /** The term in force: a renewal paid early takes over at its own start. */
    public static TermSegment segmentAt(Subscription s, Instant now) {
        TermSegment next = s.getNext();
        return next != null && !now.isBefore(next.getTermStart()) ? next : s.getCurrent();
    }

    /** The end of the last term that was paid for. */
    public static Instant lastEnd(Subscription s) {
        return s.getNext() != null ? s.getNext().getTermEnd() : s.getCurrent().getTermEnd();
    }

    /**
     * When read-only begins: {@code graceDays} calendar days after the last term's end. A plan that was cancelled
     * has no grace: it is read-only from that end.
     */
    public static Instant graceEnd(Subscription s, int graceDays, ZoneId zone) {
        if (s.isCancelled()) return lastEnd(s);
        return lastEnd(s).atZone(zone).plusDays(graceDays).toInstant();
    }

    public static SubscriptionState stateAt(Subscription s, int graceDays, ZoneId zone, Instant now) {
        if (now.isBefore(lastEnd(s))) return SubscriptionState.ACTIVE;
        if (s.isCancelled()) return SubscriptionState.EXPIRED;
        return now.isBefore(graceEnd(s, graceDays, zone)) ? SubscriptionState.GRACE : SubscriptionState.EXPIRED;
    }
}
