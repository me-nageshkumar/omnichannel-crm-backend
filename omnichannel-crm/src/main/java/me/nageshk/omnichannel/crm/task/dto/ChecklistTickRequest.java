package me.nageshk.omnichannel.crm.task.dto;

/** {@code POST /tasks/{id}/checklist/{itemId}}: tick ({@code true}) or untick one step. */
public record ChecklistTickRequest(Boolean done) {}
