package me.nageshk.omnichannel.crm.task;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Today's bounds in the viewer's time zone: overdue = due before now; today = due from now until tomorrow starts;
 * upcoming = due later, or not due at all.
 */
public record Buckets(Instant now, Instant todayStart, Instant tomorrowStart) {

    public static Buckets of(Instant now, ZoneId zone) {
        LocalDate today = now.atZone(zone).toLocalDate();
        return new Buckets(now, today.atStartOfDay(zone).toInstant(), today.plusDays(1).atStartOfDay(zone).toInstant());
    }

    public Bucket of(Instant dueAt) {
        if (dueAt == null || !dueAt.isBefore(tomorrowStart)) return Bucket.UPCOMING;
        return dueAt.isBefore(now) ? Bucket.OVERDUE : Bucket.TODAY;
    }
}
