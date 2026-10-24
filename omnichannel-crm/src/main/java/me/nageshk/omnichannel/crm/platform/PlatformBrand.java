package me.nageshk.omnichannel.crm.platform;

/** A brand (workspace) and the customer it belongs to. */
public record PlatformBrand(String id, String customerId, String name) {}
