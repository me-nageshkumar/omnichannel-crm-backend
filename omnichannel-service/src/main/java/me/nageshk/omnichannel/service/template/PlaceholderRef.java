package me.nageshk.omnichannel.service.template;

import me.nageshk.omnichannel.db.template.PlaceholderKind;

/** A placeholder found in template text; {@code name} is the normalised text between the braces. */
public record PlaceholderRef(String name, PlaceholderKind kind, String attributeKey) {}
