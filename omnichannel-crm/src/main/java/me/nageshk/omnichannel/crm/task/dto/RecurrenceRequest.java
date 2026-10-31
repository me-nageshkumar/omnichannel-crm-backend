package me.nageshk.omnichannel.crm.task.dto;

import java.util.List;

/**
 * A repeat rule as sent ({@code Recurrence}, spec E C11): the enums arrive as text so a bad value is a field error
 * rather than an unreadable body.
 */
public record RecurrenceRequest(String frequency, Integer interval, List<String> weekdays, Integer dayOfMonth,
        String timeZone, Integer endsAfter, String endsOn) {}
