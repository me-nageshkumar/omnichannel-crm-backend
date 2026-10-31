package me.nageshk.omnichannel.crm.task.dto;

import java.time.Instant;
import java.util.List;
import me.nageshk.omnichannel.crm.common.Ref;
import me.nageshk.omnichannel.crm.common.TargetType;
import me.nageshk.omnichannel.crm.task.Recurrence;
import me.nageshk.omnichannel.crm.task.TaskPriority;
import me.nageshk.omnichannel.crm.task.TaskStatus;

public record TaskView(String id, String title, String description, String type, TaskPriority priority, Instant dueAt,
        Instant reminderAt, TaskStatus status, Instant completedAt, Ref completedBy, Ref assignee, Ref assignedBy,
        Instant assignedAt, Target target, boolean overdue, List<ChecklistItemView> checklist, Recurrence recurrence,
        String seriesId, Integer occurrence, String recurringFromTaskId, String nextOccurrenceId, Long version) {

    /** The record the task is about, named for display (deal title, contact name or company name). */
    public record Target(TargetType type, String id, String name) {}

    /** One checklist step. */
    public record ChecklistItemView(String id, String text, boolean done, Instant doneAt, Ref doneBy) {}
}
