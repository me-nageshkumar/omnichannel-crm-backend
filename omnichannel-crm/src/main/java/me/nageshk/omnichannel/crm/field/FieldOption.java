package me.nageshk.omnichannel.crm.field;

/** One choice of a SELECT or MULTI_SELECT field; inactive options stay on records that already hold them. */
public record FieldOption(String key, String label, String color, int position, boolean active) {}
