package me.nageshk.omnichannel.crm.task;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class BucketsTest {

    private static final Instant NOON_UTC = Instant.parse("2026-10-24T12:00:00Z");

    @Test
    void todayBucketHonoursTimeZone() {
        Buckets kolkata = Buckets.of(NOON_UTC, ZoneId.of("Asia/Kolkata"));
        Buckets utc = Buckets.of(NOON_UTC, ZoneId.of("UTC"));
        // 23:30 in Kolkata on the 24th: today in both zones
        Instant lateEvening = Instant.parse("2026-10-24T18:00:00Z");
        assertThat(kolkata.of(lateEvening)).isEqualTo(Bucket.TODAY);
        assertThat(utc.of(lateEvening)).isEqualTo(Bucket.TODAY);
        // 00:30 in Kolkata on the 25th: upcoming there, still today in UTC
        Instant afterMidnightIst = Instant.parse("2026-10-24T19:00:00Z");
        assertThat(kolkata.of(afterMidnightIst)).isEqualTo(Bucket.UPCOMING);
        assertThat(utc.of(afterMidnightIst)).isEqualTo(Bucket.TODAY);
        assertThat(kolkata.tomorrowStart()).isEqualTo(Instant.parse("2026-10-24T18:30:00Z"));
        assertThat(kolkata.todayStart()).isEqualTo(Instant.parse("2026-10-23T18:30:00Z"));
    }

    @Test
    void pastDueIsOverdueEvenEarlierToday() {
        Buckets utc = Buckets.of(NOON_UTC, ZoneId.of("UTC"));
        assertThat(utc.of(Instant.parse("2026-10-24T11:59:00Z"))).isEqualTo(Bucket.OVERDUE);
        assertThat(utc.of(NOON_UTC)).isEqualTo(Bucket.TODAY);
    }

    @Test
    void noDueDateIsUpcoming() {
        assertThat(Buckets.of(NOON_UTC, ZoneId.of("UTC")).of(null)).isEqualTo(Bucket.UPCOMING);
    }
}
