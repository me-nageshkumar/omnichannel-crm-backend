package me.nageshk.omnichannel.crm.task;

import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Repeat rules (spec E C11, C12): validation into a normalised copy, and the next due date. Pure; every date is
 * computed on the local date-time in the rule's zone so the wall-clock time survives DST changes.
 */
public final class TaskRecurrenceSchedule {

    public static final int MAX_INTERVAL = 99;
    public static final int MIN_ENDS_AFTER = 2;
    public static final int MAX_ENDS_AFTER = 999;
    /** Guards the catch-up loop; a daily rule needs ~270 years of missed occurrences to reach it. */
    static final int MAX_STEPS = 100_000;

    private TaskRecurrenceSchedule() {}

    /**
     * A validated, cleaned copy of {@code rule} for a task first due at {@code firstDue}; every problem goes into
     * {@code errors} under its field key, and the result is null when there was any.
     */
    public static Recurrence normalize(Recurrence rule, Instant firstDue, Map<String, String> errors) {
        if (firstDue == null) {
            errors.put("recurrence", "needs a due date");
            return null;
        }
        boolean ok = true;
        if (rule.frequency() == null) {
            errors.put("recurrence.frequency", "must be DAILY, WEEKLY or MONTHLY");
            ok = false;
        }
        int interval = rule.interval() == null ? 1 : rule.interval();
        if (interval < 1 || interval > MAX_INTERVAL) {
            errors.put("recurrence.interval", "must be 1 to " + MAX_INTERVAL);
            ok = false;
        }
        ZoneId zone = null;
        String zoneId = rule.timeZone() == null || rule.timeZone().isBlank() ? "UTC" : rule.timeZone().trim();
        try {
            zone = ZoneId.of(zoneId);
        } catch (DateTimeException e) {
            errors.put("recurrence.timeZone", "must be an IANA time zone");
            ok = false;
        }
        List<DayOfWeek> weekdays = null;
        Integer dayOfMonth = null;
        if (rule.frequency() == RecurrenceFrequency.WEEKLY) {
            weekdays = rule.weekdays() == null ? List.of()
                    : rule.weekdays().stream().filter(d -> d != null).distinct().sorted().toList();
            if (weekdays.isEmpty()) {
                errors.put("recurrence.weekdays", "pick at least one weekday");
                ok = false;
            }
        } else if (rule.frequency() == RecurrenceFrequency.MONTHLY) {
            dayOfMonth = rule.dayOfMonth() != null ? rule.dayOfMonth()
                    : zone == null ? null : firstDue.atZone(zone).getDayOfMonth();
            if (dayOfMonth != null && (dayOfMonth < 1 || dayOfMonth > 31)) {
                errors.put("recurrence.dayOfMonth", "must be 1 to 31");
                ok = false;
            }
        }
        if (rule.endsAfter() != null && rule.endsOn() != null) {
            errors.put("recurrence", "set endsAfter or endsOn, not both");
            ok = false;
        }
        if (rule.endsAfter() != null && (rule.endsAfter() < MIN_ENDS_AFTER || rule.endsAfter() > MAX_ENDS_AFTER)) {
            errors.put("recurrence.endsAfter", "must be " + MIN_ENDS_AFTER + " to " + MAX_ENDS_AFTER);
            ok = false;
        }
        String endsOn = null;
        if (rule.endsOn() != null) {
            try {
                LocalDate end = LocalDate.parse(rule.endsOn().trim());
                if (zone != null && end.isBefore(firstDue.atZone(zone).toLocalDate())) {
                    errors.put("recurrence.endsOn", "must not be before the due date");
                    ok = false;
                }
                endsOn = end.toString();
            } catch (DateTimeParseException e) {
                errors.put("recurrence.endsOn", "must be yyyy-mm-dd");
                ok = false;
            }
        }
        if (!ok) return null;
        return new Recurrence(rule.frequency(), interval, weekdays, dayOfMonth, zone.getId(), rule.endsAfter(), endsOn);
    }

    /**
     * The due date of the occurrence after {@code currentOccurrence}, stepping from {@code currentDue} until strictly
     * after {@code now} (missed steps are skipped and not counted); empty when the series has ended.
     */
    public static Optional<Instant> nextDue(Recurrence rule, Instant currentDue, int currentOccurrence, Instant now) {
        if (rule.endsAfter() != null && currentOccurrence + 1 > rule.endsAfter()) return Optional.empty();
        ZoneId zone = ZoneId.of(rule.timeZone() == null ? "UTC" : rule.timeZone());
        ZonedDateTime due = currentDue.atZone(zone);
        int steps = 0;
        do {
            due = step(rule, due);
            if (++steps > MAX_STEPS) return Optional.empty();
        } while (!due.toInstant().isAfter(now));
        if (rule.endsOn() != null && due.toLocalDate().isAfter(LocalDate.parse(rule.endsOn()))) return Optional.empty();
        return Optional.of(due.toInstant());
    }

    private static ZonedDateTime step(Recurrence rule, ZonedDateTime due) {
        LocalDate date = due.toLocalDate();
        int interval = rule.interval() == null ? 1 : rule.interval();
        LocalDate next = switch (rule.frequency()) {
            case DAILY -> date.plusDays(interval);
            case WEEKLY -> nextWeekly(date, rule.weekdays(), interval);
            case MONTHLY -> {
                YearMonth month = YearMonth.from(date).plusMonths(interval);
                yield month.atDay(Math.min(rule.dayOfMonth(), month.lengthOfMonth()));
            }
        };
        // ZonedDateTime.of moves a time inside a DST gap forward
        return ZonedDateTime.of(next, due.toLocalTime(), due.getZone());
    }

    /** The first listed weekday later in the same Monday-start week, else the first listed one {@code interval} weeks on. */
    private static LocalDate nextWeekly(LocalDate date, List<DayOfWeek> weekdays, int interval) {
        for (DayOfWeek day : weekdays) {
            if (day.compareTo(date.getDayOfWeek()) > 0) return date.with(day);
        }
        return date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusWeeks(interval).with(weekdays.getFirst());
    }
}
