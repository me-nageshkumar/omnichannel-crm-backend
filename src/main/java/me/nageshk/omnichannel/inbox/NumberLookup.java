package me.nageshk.omnichannel.inbox;

import java.time.Instant;

/**
 * Whether a phone is on WhatsApp. {@code remoteId} is the chat id to use ({@code @lid} when WhatsApp reported one,
 * else {@code @c.us}), null when not registered; {@code cached} tells the answer came from an earlier check.
 */
public record NumberLookup(String phone, boolean registered, String remoteId, Instant checkedAt, boolean cached) {
}
