package me.nageshk.omnichannel.crm.platform;

/** One connected account a brand messages from (today a WhatsApp number); {@code address} is what people dial. */
public record ChannelAccount(String id, ChannelKind kind, String label, String address) {}
