package me.nageshk.omnichannel.crm.task;

import java.time.DayOfWeek;
import java.util.List;

/**
 * How a task repeats (spec E C11); stored normalised by {@code TaskRecurrenceSchedule}. At most one of
 * {@code endsAfter} (occurrences counting the first) and {@code endsOn} ({@code yyyy-MM-dd} in {@code timeZone}) is
 * set; neither means forever.
 */
public record Recurrence(RecurrenceFrequency frequency, Integer interval, List<DayOfWeek> weekdays, Integer dayOfMonth,
        String timeZone, Integer endsAfter, String endsOn) {}
