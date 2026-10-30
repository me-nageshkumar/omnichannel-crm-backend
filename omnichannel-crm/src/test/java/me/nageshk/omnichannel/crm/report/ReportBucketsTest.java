package me.nageshk.omnichannel.crm.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.Test;

class ReportBucketsTest {

    private static final ZoneId UTC = ZoneId.of("UTC");

    private static Instant at(String iso) {
        return Instant.parse(iso);
    }

    @Test
    void autoBucketByRangeLength() {
        Instant from = at("2026-01-01T00:00:00Z");
        assertThat(ReportBuckets.auto(from, from.plus(java.time.Duration.ofDays(31)))).isEqualTo(ReportBucket.DAY);
        assertThat(ReportBuckets.auto(from, from.plus(java.time.Duration.ofDays(32)))).isEqualTo(ReportBucket.WEEK);
        assertThat(ReportBuckets.auto(from, from.plus(java.time.Duration.ofDays(182)))).isEqualTo(ReportBucket.WEEK);
        assertThat(ReportBuckets.auto(from, from.plus(java.time.Duration.ofDays(183)))).isEqualTo(ReportBucket.MONTH);
    }

    @Test
    void weekStartsOnMonday() {
        // 2026-10-30 is a Friday; the week containing it starts Monday 2026-10-26
        List<Instant> starts = ReportBuckets.starts(at("2026-10-30T10:00:00Z"), at("2026-11-10T00:00:00Z"), UTC,
                ReportBucket.WEEK);
        assertThat(starts).containsExactly(at("2026-10-26T00:00:00Z"), at("2026-11-02T00:00:00Z"),
                at("2026-11-09T00:00:00Z"));
    }

    @Test
    void monthBucketsInZone() {
        ZoneId kolkata = ZoneId.of("Asia/Kolkata");
        List<Instant> starts = ReportBuckets.starts(at("2026-08-31T20:00:00Z"), at("2026-10-30T10:00:00Z"), kolkata,
                ReportBucket.MONTH);
        // 2026-08-31T20:00Z is already 1 Sep 01:30 in Kolkata, so September is the first month
        assertThat(starts).containsExactly(at("2026-08-31T18:30:00Z"), at("2026-09-30T18:30:00Z"));
    }

    @Test
    void bucketsAcrossDstChange() {
        ZoneId london = ZoneId.of("Europe/London");
        // clocks go back on 2026-10-25; every day is still one bucket at local midnight
        List<Instant> starts = ReportBuckets.starts(at("2026-10-20T12:00:00Z"), at("2026-11-02T00:00:00Z"), london,
                ReportBucket.DAY);
        assertThat(starts).hasSize(13);
        assertThat(starts.get(0)).isEqualTo(at("2026-10-19T23:00:00Z")); // BST midnight
        assertThat(starts.get(5)).isEqualTo(at("2026-10-24T23:00:00Z")); // 25 Oct, still BST at midnight
        assertThat(starts.get(6)).isEqualTo(at("2026-10-26T00:00:00Z")); // 26 Oct, GMT midnight
        assertThat(starts.get(12)).isEqualTo(at("2026-11-01T00:00:00Z"));
        assertThat(starts.stream().distinct()).hasSize(13);
    }

    @Test
    void truncExpressionShape() {
        Document d = ReportBuckets.truncExpression("closedAt", ReportBucket.WEEK, ZoneId.of("Asia/Kolkata"));
        Document trunc = d.get("$dateTrunc", Document.class);
        assertThat(trunc.getString("date")).isEqualTo("$closedAt");
        assertThat(trunc.getString("unit")).isEqualTo("week");
        assertThat(trunc.getString("timezone")).isEqualTo("Asia/Kolkata");
        assertThat(trunc.getString("startOfWeek")).isEqualTo("monday");
        assertThat(ReportBuckets.truncExpression("x", ReportBucket.DAY, UTC).get("$dateTrunc", Document.class))
                .doesNotContainKey("startOfWeek");
    }
}
