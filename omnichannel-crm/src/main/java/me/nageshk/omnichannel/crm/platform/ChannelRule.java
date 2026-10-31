package me.nageshk.omnichannel.crm.platform;

/**
 * One condition on channel contacts: {@code field} is {@code label}, {@code attribute:<id>}, {@code lastMessageAt},
 * {@code source} or {@code doNotContact}; date values are ISO instants (the CRM has already turned relative and
 * zone-day values into instants), operators are the segment operators of the field's type.
 */
public record ChannelRule(String field, String operator, Object value) {}
