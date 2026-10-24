package me.nageshk.omnichannel.crm.activity;

/** One changed field of a record: its value before and after. */
public record FieldDiff(String field, Object from, Object to) {}
