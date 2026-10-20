package me.nageshk.omnichannel.template;

/** A placeholder found in template text; {@code name} is the normalised text between the braces. */
public record PlaceholderRef(String name, PlaceholderKind kind, String attributeKey) {}
