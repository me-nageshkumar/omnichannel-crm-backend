package me.nageshk.omnichannel.common.channel;

/** Who an event belongs to; {@code channelAccountId} is the phone number id today. */
public record ChannelContext(
        ChannelKind kind,
        String customerId,
        String workspaceId,
        String channelAccountId,
        String displayPhoneNumber) {}
