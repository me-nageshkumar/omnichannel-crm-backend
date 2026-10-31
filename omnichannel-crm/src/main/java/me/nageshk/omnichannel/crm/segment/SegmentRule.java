package me.nageshk.omnichannel.crm.segment;

/** One condition; {@code value} is a String, a List of Strings or null, as Jackson or Mongo read it. */
public record SegmentRule(String field, String operator, Object value) implements SegmentRuleNode {}
