package me.nageshk.omnichannel.meta.api;

/** Which Meta number to add: the connection whose token reaches it, its WABA, and Meta's phone number id. */
public record CloudNumberRequest(String connectionId, String wabaId, String metaPhoneNumberId) {}
