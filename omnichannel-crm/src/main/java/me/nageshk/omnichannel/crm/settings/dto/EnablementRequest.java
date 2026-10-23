package me.nageshk.omnichannel.crm.settings.dto;

/** {@code enabled} is required; a null limit means unlimited, otherwise it must be at least 1. */
public record EnablementRequest(Boolean enabled, Integer maxPipelines, Integer maxCustomFields) {}
