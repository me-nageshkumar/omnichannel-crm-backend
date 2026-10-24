package me.nageshk.omnichannel.crm.task.dto;

import java.time.Instant;
import me.nageshk.omnichannel.crm.common.Ref;
import me.nageshk.omnichannel.crm.common.TargetType;
import me.nageshk.omnichannel.crm.task.TaskPriority;
import me.nageshk.omnichannel.crm.task.TaskStatus;

public record TaskView(String id, String title, String description, String type, TaskPriority priority, Instant dueAt,
        Instant reminderAt, TaskStatus status, Instant completedAt, Ref completedBy, Ref assignee, Target target,
        boolean overdue, Long version) {

    /** The record the task is about, named for display (deal title, contact name or company name). */
    public record Target(TargetType type, String id, String name) {}
}
