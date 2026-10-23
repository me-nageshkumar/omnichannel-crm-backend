package me.nageshk.omnichannel.db.contact.advanced;

import java.util.List;

/** The attribute and label ids a rule tree refers to, sorted and distinct. */
public record Uses(List<String> attributeIds, List<String> labelIds) {}
