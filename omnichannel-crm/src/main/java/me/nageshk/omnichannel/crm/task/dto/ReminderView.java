package me.nageshk.omnichannel.crm.task.dto;

import java.time.Instant;

/** A due follow-up the assignee has not dismissed yet. */
public record ReminderView(String taskId, String title, Instant dueAt, TaskView.Target target) {}
