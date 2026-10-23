package me.nageshk.omnichannel.service.contact.advanced;

import java.time.ZoneId;
import java.util.Set;

/** What a workspace has: its attribute ids, label ids and the zone dates are read in. */
public record Resolved(Set<String> attributeIds, Set<String> labelIds, ZoneId zone) {}
