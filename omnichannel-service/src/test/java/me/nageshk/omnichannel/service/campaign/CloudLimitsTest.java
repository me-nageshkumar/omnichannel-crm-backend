package me.nageshk.omnichannel.service.campaign;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CloudLimitsTest {

    @Test
    void tiers() {
        assertThat(CloudLimits.dailyLimit("TIER_50")).isEqualTo(50);
        assertThat(CloudLimits.dailyLimit("TIER_250")).isEqualTo(250);
        assertThat(CloudLimits.dailyLimit("TIER_1K")).isEqualTo(1000);
        assertThat(CloudLimits.dailyLimit("TIER_2K")).isEqualTo(2000);
        assertThat(CloudLimits.dailyLimit("TIER_10K")).isEqualTo(10000);
        assertThat(CloudLimits.dailyLimit("TIER_100K")).isEqualTo(100000);
        assertThat(CloudLimits.dailyLimit("TIER_UNLIMITED")).isNull();
        assertThat(CloudLimits.dailyLimit(null)).isEqualTo(250);
        assertThat(CloudLimits.dailyLimit("SOMETHING")).isEqualTo(250);
    }
}
