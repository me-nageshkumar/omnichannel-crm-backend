package me.nageshk.omnichannel.crm.segment.dto;

import java.time.Instant;
import java.util.Map;
import me.nageshk.omnichannel.crm.segment.CachedCounts;

/**
 * A segment as the console sees it. {@code rules} is the saved tree with, per rule, {@code missing: true} when its
 * field no longer exists or {@code inaccessible: true} when it names a pipeline or stage the caller cannot see.
 */
public record SegmentView(String id, String name, String description, Map<String, Object> rules,
        CachedCounts cachedCounts, Instant lastSentAt, String lastCampaignId, String lastCampaignName,
        Instant creationTime, Instant lastUpdateTime, Long version) {}
