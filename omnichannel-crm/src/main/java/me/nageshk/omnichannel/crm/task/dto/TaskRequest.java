package me.nageshk.omnichannel.crm.task.dto;

import java.time.Instant;
import me.nageshk.omnichannel.crm.common.CrmTarget;

/**
 * Create a task (target required) or replace an existing task's editable fields: on update a null field clears it,
 * except {@code assigneeId} (null keeps the assignee) and {@code target} (fixed after creation).
 */
public record TaskRequest(String title, String description, String type, String priority, Instant dueAt,
        Instant reminderAt, String assigneeId, CrmTarget target, Long version) {}
