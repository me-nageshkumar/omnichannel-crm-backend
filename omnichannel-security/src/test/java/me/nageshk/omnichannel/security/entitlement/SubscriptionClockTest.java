package me.nageshk.omnichannel.security.entitlement;

import static me.nageshk.omnichannel.common.billing.SubscriptionState.ACTIVE;
import static me.nageshk.omnichannel.common.billing.SubscriptionState.EXPIRED;
import static me.nageshk.omnichannel.common.billing.SubscriptionState.GRACE;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.ZoneId;
import me.nageshk.omnichannel.db.billing.Subscription;
import me.nageshk.omnichannel.db.billing.TermSegment;
import org.junit.jupiter.api.Test;

class SubscriptionClockTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    // 1 Dec 2026 to 1 Jan 2027, midnights in India
    private static final Instant START = Instant.parse("2026-11-30T18:30:00Z");
    private static final Instant END = Instant.parse("2026-12-31T18:30:00Z");

    @Test
    void theTermEndsAtMidnightInTheBillingZone() {
        Subscription sub = subscription(segment(START, END), null);
        assertThat(SubscriptionClock.stateAt(sub, 7, IST, Instant.parse("2026-12-31T18:29:59.999Z"))).isEqualTo(ACTIVE);
        assertThat(SubscriptionClock.stateAt(sub, 7, IST, END)).isEqualTo(GRACE);
        assertThat(SubscriptionClock.stateAt(sub, 7, IST, Instant.parse("2027-01-07T18:29:59.999Z"))).isEqualTo(GRACE);
        assertThat(SubscriptionClock.stateAt(sub, 7, IST, Instant.parse("2027-01-07T18:30:00Z"))).isEqualTo(EXPIRED);
        assertThat(SubscriptionClock.graceEnd(sub, 7, IST)).isEqualTo(Instant.parse("2027-01-07T18:30:00Z"));
    }

    @Test
    void withoutGraceTheTermEndIsTheExpiry() {
        Subscription sub = subscription(segment(START, END), null);
        assertThat(SubscriptionClock.stateAt(sub, 0, IST, END)).isEqualTo(EXPIRED);
    }

    @Test
    void aRenewalPaidEarlyTakesOverAtItsOwnStart() {
        TermSegment current = segment(START, END);
        TermSegment next = segment(END, Instant.parse("2027-01-31T18:30:00Z"));
        Subscription sub = subscription(current, next);

        assertThat(SubscriptionClock.segmentAt(sub, Instant.parse("2026-12-15T00:00:00Z"))).isSameAs(current);
        assertThat(SubscriptionClock.segmentAt(sub, END)).isSameAs(next);
        assertThat(SubscriptionClock.stateAt(sub, 7, IST, Instant.parse("2027-01-15T00:00:00Z"))).isEqualTo(ACTIVE);
        assertThat(SubscriptionClock.lastEnd(sub)).isEqualTo(Instant.parse("2027-01-31T18:30:00Z"));
    }

    static TermSegment segment(Instant start, Instant end) {
        TermSegment s = new TermSegment();
        s.setTermStart(start);
        s.setTermEnd(end);
        return s;
    }

    static Subscription subscription(TermSegment current, TermSegment next) {
        Subscription s = new Subscription();
        s.setCustomerId("c1");
        s.setCurrent(current);
        s.setNext(next);
        return s;
    }
}
