package me.nageshk.omnichannel.crm.task.dto;

/** A checklist step as sent on save: a known {@code id} keeps its tick, no id (or an unknown one) is a new step. */
public record ChecklistItemRequest(String id, String text) {}
