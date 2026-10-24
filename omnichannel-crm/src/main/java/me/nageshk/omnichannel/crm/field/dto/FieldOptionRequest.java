package me.nageshk.omnichannel.crm.field.dto;

/** One option of a SELECT/MULTI_SELECT field as sent by the console; list order is the display order. */
public record FieldOptionRequest(String key, String label, String color, Boolean active) {}
