package me.nageshk.omnichannel.crm.segment;

import java.time.Instant;
import java.util.List;

/** A segment's brand-wide size as last computed (spec C9, C20): records, people and records per channel account. */
public record CachedCounts(long total, long people, boolean truncated, List<ChannelCount> channelAccounts, Instant at) {

    public record ChannelCount(String channelAccountId, long count) {}
}
