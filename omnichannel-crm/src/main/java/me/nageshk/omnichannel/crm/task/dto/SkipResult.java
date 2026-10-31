package me.nageshk.omnichannel.crm.task.dto;

/** {@code POST /tasks/{id}/skip}: the cancelled occurrence and the one scheduled in its place (null when the series ended). */
public record SkipResult(TaskView skipped, TaskView next) {}
