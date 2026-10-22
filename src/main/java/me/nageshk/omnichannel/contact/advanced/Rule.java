package me.nageshk.omnichannel.contact.advanced;

/** One condition; {@code value} is a String, a List or null, as Jackson or Mongo read it. */
public record Rule(String field, String operator, Object value) implements RuleNode {}
