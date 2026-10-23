package me.nageshk.omnichannel.service.contact;

/** API view of a contact attribute. */
public record AttributeView(String id, String key, String name, String description, long contactCount) {}
