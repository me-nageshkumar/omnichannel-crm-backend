package me.nageshk.omnichannel.crm.field.dto;

import java.util.List;

/** The new order of one entity type's fields: every id given, first to last. */
public record ReorderRequest(String entityType, List<String> ids) {}
