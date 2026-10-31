package me.nageshk.omnichannel.crm.task.dto;

import java.time.Instant;
import me.nageshk.omnichannel.crm.common.Ref;
import me.nageshk.omnichannel.crm.task.ReminderKind;

/** A notice the assignee has not dismissed yet: a due follow-up, or a task handed to them (spec E C4). */
public record ReminderView(String taskId, String title, Instant dueAt, TaskView.Target target, ReminderKind kind,
        Ref assignedBy) {}
