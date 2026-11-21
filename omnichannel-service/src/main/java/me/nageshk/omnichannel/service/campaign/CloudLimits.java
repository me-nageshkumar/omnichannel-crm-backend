package me.nageshk.omnichannel.service.campaign;

import java.util.Map;

/** Meta's messaging limit tiers: how many people a business may start conversations with in a rolling 24 hours. */
public final class CloudLimits {

    /** What an unknown or missing tier is taken as: Meta's starting tier. */
    static final int DEFAULT_LIMIT = 250;

    private static final Map<String, Integer> TIERS = Map.of("TIER_50", 50, "TIER_250", 250, "TIER_1K", 1000,
            "TIER_2K", 2000, "TIER_10K", 10000, "TIER_100K", 100000);

    private CloudLimits() {
    }

    /** The limit of {@code tier}; null when unlimited. */
    public static Integer dailyLimit(String tier) {
        if ("TIER_UNLIMITED".equals(tier)) return null;
        return TIERS.getOrDefault(tier == null ? "" : tier, DEFAULT_LIMIT);
    }
}
