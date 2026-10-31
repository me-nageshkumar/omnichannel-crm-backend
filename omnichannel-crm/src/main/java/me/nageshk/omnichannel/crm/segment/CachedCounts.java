package me.nageshk.omnichannel.crm.segment;

import java.time.Instant;
import java.util.List;

/**
 * A segment's brand-wide size as last computed (spec C9, C20): records, people and records per channel account.
 * When {@code truncated}, {@code total} is the true size but {@code people} and {@code channelAccounts} cover only
 * the members loaded ({@link SegmentMembers#MAX_MEMBERS}), so the per-account counts sum to that cap, not to the total.
 */
public record CachedCounts(long total, long people, boolean truncated, List<ChannelCount> channelAccounts, Instant at) {

    public record ChannelCount(String channelAccountId, long count) {}
}
