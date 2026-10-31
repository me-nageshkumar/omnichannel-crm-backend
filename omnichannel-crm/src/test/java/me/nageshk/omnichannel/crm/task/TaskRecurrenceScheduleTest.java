package me.nageshk.omnichannel.crm.task;

import static java.time.DayOfWeek.MONDAY;
import static java.time.DayOfWeek.THURSDAY;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TaskRecurrenceScheduleTest {

    private static final Instant NOV_1 = Instant.parse("2026-11-01T00:00:00Z");

    private static Recurrence daily(int interval) {
        return new Recurrence(RecurrenceFrequency.DAILY, interval, null, null, "UTC", null, null);
    }

    private static Recurrence weekly(int interval, DayOfWeek... days) {
        return new Recurrence(RecurrenceFrequency.WEEKLY, interval, List.of(days), null, "UTC", null, null);
    }

    private static Recurrence monthly(int day) {
        return new Recurrence(RecurrenceFrequency.MONTHLY, 1, null, day, "UTC", null, null);
    }

    private static Instant at(String iso) {
        return Instant.parse(iso);
    }

    private static Instant next(Recurrence rule, String currentDue, Instant now) {
        return TaskRecurrenceSchedule.nextDue(rule, at(currentDue), 1, now).orElseThrow();
    }

    @Test
    void dailyEveryTwoDays() {
        assertThat(next(daily(2), "2026-11-02T10:00:00Z", NOV_1)).isEqualTo(at("2026-11-04T10:00:00Z"));
    }

    @Test
    void weeklyMonThuWithinWeekThenJumps() {
        assertThat(next(weekly(1, MONDAY, THURSDAY), "2026-11-02T10:00:00Z", NOV_1)).isEqualTo(at("2026-11-05T10:00:00Z"));
        assertThat(next(weekly(1, MONDAY, THURSDAY), "2026-11-05T10:00:00Z", NOV_1)).isEqualTo(at("2026-11-09T10:00:00Z"));
        assertThat(next(weekly(2, MONDAY, THURSDAY), "2026-11-05T10:00:00Z", NOV_1)).isEqualTo(at("2026-11-16T10:00:00Z"));
    }

    @Test
    void weeklyFromUnlistedDayGoesToNextListed() {
        // 3 Nov 2026 is a Tuesday, 6 Nov a Friday
        assertThat(next(weekly(1, MONDAY, THURSDAY), "2026-11-03T10:00:00Z", NOV_1)).isEqualTo(at("2026-11-05T10:00:00Z"));
        assertThat(next(weekly(1, MONDAY, THURSDAY), "2026-11-06T10:00:00Z", NOV_1)).isEqualTo(at("2026-11-09T10:00:00Z"));
    }

    @Test
    void monthlyClampsToMonthEnd() {
        Instant jan = Instant.parse("2027-01-01T00:00:00Z");
        assertThat(next(monthly(31), "2027-01-31T09:00:00Z", jan)).isEqualTo(at("2027-02-28T09:00:00Z"));
        assertThat(next(monthly(31), "2027-02-28T09:00:00Z", jan)).isEqualTo(at("2027-03-31T09:00:00Z"));
    }

    @Test
    void monthlyLeapYear() {
        assertThat(next(monthly(31), "2028-01-31T09:00:00Z", Instant.parse("2028-01-01T00:00:00Z")))
                .isEqualTo(at("2028-02-29T09:00:00Z"));
    }

    @Test
    void keepsWallClockAcrossDst() {
        // London enters BST on 28 Mar 2027: 10:00 local stays 10:00 local, which is 09:00Z
        Recurrence rule = new Recurrence(RecurrenceFrequency.DAILY, 1, null, null, "Europe/London", null, null);
        assertThat(next(rule, "2027-03-27T10:00:00Z", Instant.parse("2027-03-01T00:00:00Z")))
                .isEqualTo(at("2027-03-28T09:00:00Z"));
    }

    @Test
    void skipsMissedOccurrences() {
        Instant now = Instant.parse("2026-11-01T10:30:00Z");
        assertThat(next(daily(1), "2026-10-02T10:00:00Z", now)).isEqualTo(at("2026-11-02T10:00:00Z"));
    }

    @Test
    void endsAfterStopsSeries() {
        Recurrence rule = new Recurrence(RecurrenceFrequency.DAILY, 1, null, null, "UTC", 3, null);
        assertThat(TaskRecurrenceSchedule.nextDue(rule, at("2026-11-02T10:00:00Z"), 2, NOV_1)).isPresent();
        assertThat(TaskRecurrenceSchedule.nextDue(rule, at("2026-11-02T10:00:00Z"), 3, NOV_1)).isEqualTo(Optional.empty());
    }

    @Test
    void endsOnStopsSeries() {
        Recurrence rule = new Recurrence(RecurrenceFrequency.DAILY, 1, null, null, "UTC", null, "2026-11-03");
        assertThat(next(rule, "2026-11-02T10:00:00Z", NOV_1)).isEqualTo(at("2026-11-03T10:00:00Z"));
        assertThat(TaskRecurrenceSchedule.nextDue(rule, at("2026-11-03T10:00:00Z"), 2, NOV_1)).isEqualTo(Optional.empty());
    }

    private static Map<String, String> errorsOf(Recurrence rule, Instant due) {
        Map<String, String> errors = new LinkedHashMap<>();
        TaskRecurrenceSchedule.normalize(rule, due, errors);
        return errors;
    }

    @Test
    void normalizeCollectsErrors() {
        Instant due = at("2026-11-02T10:00:00Z");
        assertThat(errorsOf(daily(0), due)).containsEntry("recurrence.interval", "must be 1 to 99");
        assertThat(errorsOf(daily(100), due)).containsEntry("recurrence.interval", "must be 1 to 99");
        assertThat(errorsOf(new Recurrence(null, 1, null, null, "UTC", null, null), due))
                .containsEntry("recurrence.frequency", "must be DAILY, WEEKLY or MONTHLY");
        assertThat(errorsOf(weekly(1), due)).containsEntry("recurrence.weekdays", "pick at least one weekday");
        assertThat(errorsOf(new Recurrence(RecurrenceFrequency.DAILY, 1, null, null, "UTC", 5, "2026-12-01"), due))
                .containsEntry("recurrence", "set endsAfter or endsOn, not both");
        assertThat(errorsOf(new Recurrence(RecurrenceFrequency.DAILY, 1, null, null, "Mars/Olympus", null, null), due))
                .containsEntry("recurrence.timeZone", "must be an IANA time zone");
        assertThat(errorsOf(new Recurrence(RecurrenceFrequency.DAILY, 1, null, null, "UTC", 1, null), due))
                .containsEntry("recurrence.endsAfter", "must be 2 to 999");
        assertThat(errorsOf(new Recurrence(RecurrenceFrequency.DAILY, 1, null, null, "UTC", 1000, null), due))
                .containsEntry("recurrence.endsAfter", "must be 2 to 999");
        assertThat(errorsOf(new Recurrence(RecurrenceFrequency.DAILY, 1, null, null, "UTC", null, "01/12/2026"), due))
                .containsEntry("recurrence.endsOn", "must be yyyy-mm-dd");
        assertThat(errorsOf(new Recurrence(RecurrenceFrequency.DAILY, 1, null, null, "UTC", null, "2026-11-01"), due))
                .containsEntry("recurrence.endsOn", "must not be before the due date");
        assertThat(errorsOf(monthly(32), due)).containsEntry("recurrence.dayOfMonth", "must be 1 to 31");
        Map<String, String> several = errorsOf(new Recurrence(RecurrenceFrequency.WEEKLY, 0, null, null, "UTC", 1, null), due);
        assertThat(several).containsOnlyKeys("recurrence.interval", "recurrence.weekdays", "recurrence.endsAfter");
    }

    @Test
    void normalizeNeedsDue() {
        Map<String, String> errors = new LinkedHashMap<>();
        assertThat(TaskRecurrenceSchedule.normalize(daily(1), null, errors)).isNull();
        assertThat(errors).containsExactly(Map.entry("recurrence", "needs a due date"));
    }

    @Test
    void normalizeTakesDayOfMonthFromFirstDue() {
        Map<String, String> errors = new LinkedHashMap<>();
        // 31 Jan 2027 18:30Z is already 1 Feb in Kolkata? No: 18:30Z = 00:00 IST on 1 Feb, so use 12:00Z = 17:30 IST
        Recurrence clean = TaskRecurrenceSchedule.normalize(
                new Recurrence(RecurrenceFrequency.MONTHLY, null, null, null, "Asia/Kolkata", null, null),
                at("2027-01-31T12:00:00Z"), errors);
        assertThat(errors).isEmpty();
        assertThat(clean.dayOfMonth()).isEqualTo(31);
        assertThat(clean.interval()).isEqualTo(1);
        assertThat(clean.timeZone()).isEqualTo("Asia/Kolkata");
    }

    @Test
    void normalizeCleansWeekdaysAndDefaults() {
        Map<String, String> errors = new LinkedHashMap<>();
        Recurrence weekly = TaskRecurrenceSchedule.normalize(new Recurrence(RecurrenceFrequency.WEEKLY, 1,
                List.of(THURSDAY, MONDAY, MONDAY), 15, " ", null, null), at("2026-11-02T10:00:00Z"), errors);
        assertThat(errors).isEmpty();
        assertThat(weekly.weekdays()).containsExactly(MONDAY, THURSDAY);
        assertThat(weekly.timeZone()).isEqualTo("UTC");
        assertThat(weekly.dayOfMonth()).isNull();
        Recurrence dailyRule = TaskRecurrenceSchedule.normalize(new Recurrence(RecurrenceFrequency.DAILY, null,
                List.of(MONDAY), 3, null, null, " 2026-12-01 "), at("2026-11-02T10:00:00Z"), errors);
        assertThat(errors).isEmpty();
        assertThat(dailyRule.weekdays()).isNull();
        assertThat(dailyRule.dayOfMonth()).isNull();
        assertThat(dailyRule.interval()).isEqualTo(1);
        assertThat(dailyRule.endsOn()).isEqualTo("2026-12-01");
    }
}
