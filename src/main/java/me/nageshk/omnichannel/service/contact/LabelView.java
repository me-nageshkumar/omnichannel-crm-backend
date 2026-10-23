package me.nageshk.omnichannel.service.contact;

/** API view of a contact label. */
public record LabelView(String id, String name, String color, String description, long contactCount) {}
