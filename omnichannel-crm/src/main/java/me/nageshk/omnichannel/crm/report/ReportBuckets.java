package me.nageshk.omnichannel.crm.report;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.bson.Document;

/** Time buckets in the caller's zone (spec C6): the bucket to use, the zero-fill list, and the Mongo expression. */
public final class ReportBuckets {

    private ReportBuckets() {}

    /** Days up to 31 → DAY, up to 182 → WEEK, else MONTH. */
    public static ReportBucket auto(Instant from, Instant to) {
        long days = Duration.between(from, to).toDays();
        if (days <= 31) return ReportBucket.DAY;
        return days <= 182 ? ReportBucket.WEEK : ReportBucket.MONTH;
    }

    /** Every bucket start from the bucket containing {@code from} while it is before {@code to}, in {@code zone}. */
    public static List<Instant> starts(Instant from, Instant to, ZoneId zone, ReportBucket bucket) {
        List<Instant> starts = new ArrayList<>();
        for (LocalDate d = truncate(from.atZone(zone).toLocalDate(), bucket); ; d = next(d, bucket)) {
            Instant start = d.atStartOfDay(zone).toInstant();
            if (!start.isBefore(to)) break;
            starts.add(start);
        }
        return starts;
    }

    /** {@code {$dateTrunc: {date: "$field", unit, timezone[, startOfWeek: "monday"]}}}. */
    public static Document truncExpression(String field, ReportBucket bucket, ZoneId zone) {
        Document trunc = new Document("date", "$" + field).append("unit", bucket.name().toLowerCase(Locale.ROOT))
                .append("timezone", zone.getId());
        if (bucket == ReportBucket.WEEK) trunc.append("startOfWeek", "monday");
        return new Document("$dateTrunc", trunc);
    }

    private static LocalDate truncate(LocalDate d, ReportBucket bucket) {
        return switch (bucket) {
            case DAY -> d;
            case WEEK -> d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            case MONTH -> d.withDayOfMonth(1);
        };
    }

    private static LocalDate next(LocalDate d, ReportBucket bucket) {
        return switch (bucket) {
            case DAY -> d.plusDays(1);
            case WEEK -> d.plusWeeks(1);
            case MONTH -> d.plusMonths(1);
        };
    }
}
