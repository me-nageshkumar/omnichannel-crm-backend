package me.nageshk.omnichannel.crm.common;

/** One entry of a settings picklist; never removed, only deactivated. */
public record PicklistItem(String key, String label, String color, int position, boolean active, boolean isDefault) {}
