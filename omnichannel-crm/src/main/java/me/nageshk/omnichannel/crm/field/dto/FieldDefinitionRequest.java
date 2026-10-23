package me.nageshk.omnichannel.crm.field.dto;

import java.util.List;

/** Create or update a field definition; {@code entityType}, {@code key} and {@code type} cannot change later. */
public record FieldDefinitionRequest(String entityType, String key, String label, String type,
        List<FieldOptionRequest> options, Boolean required, Object defaultValue, Boolean showInList,
        Boolean showInCard, Boolean active, Long version) {}
