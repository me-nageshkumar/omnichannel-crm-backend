package me.nageshk.omnichannel.crm.task.dto;

/** The viewed assignee's open tasks per bucket, and the caller's own open hand-offs ({@code delegated}). */
public record TaskCounts(long overdue, long today, long upcoming, long delegated) {}
