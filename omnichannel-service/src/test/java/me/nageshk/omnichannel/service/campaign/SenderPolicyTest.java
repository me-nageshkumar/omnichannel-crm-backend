package me.nageshk.omnichannel.service.campaign;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import me.nageshk.omnichannel.db.campaign.CampaignSettings;
import org.junit.jupiter.api.Test;

class SenderPolicyTest {

    /** Wednesday 11:00 in Asia/Kolkata, as in the campaign ITs. */
    private static final Instant WED_11_IST = Instant.parse("2026-10-21T05:30:00Z");

    /** Picks either bound of every range. */
    private static final class FixedRandom extends PacingRandom {
        boolean max = false;

        @Override
        public int seconds(int minInclusive, int maxInclusive) {
            return max ? maxInclusive : minInclusive;
        }
    }

    private final CampaignSettings s = CampaignSettingsService.defaults("ws");

    @Test
    void ageCountsSendingDaysNotCalendarDays() {
        LocalDate wed = LocalDate.of(2026, 10, 21);
        assertThat(SenderPolicy.ageDay(0, null, wed)).isEqualTo(1);
        assertThat(SenderPolicy.ageDay(1, wed, wed)).isEqualTo(1);
        assertThat(SenderPolicy.ageDay(1, wed.minusDays(9), wed)).isEqualTo(2);
    }

    @Test
    void capFollowsTheWarmupTable() {
        Map.ofEntries(Map.entry(1, 5), Map.entry(3, 5), Map.entry(4, 10), Map.entry(7, 10), Map.entry(8, 20),
                Map.entry(14, 20), Map.entry(15, 35), Map.entry(21, 35), Map.entry(22, 50), Map.entry(30, 50),
                Map.entry(31, 100))
                .forEach((age, cap) -> assertThat(SenderPolicy.cap(age, false, s)).as("age %d", age).isEqualTo(cap));
    }

    @Test
    void lowReplyRateHalves() {
        assertThat(SenderPolicy.cap(1, true, s)).isEqualTo(2);
        assertThat(SenderPolicy.cap(22, true, s)).isEqualTo(25);
        assertThat(SenderPolicy.cap(31, true, s)).isEqualTo(37);
    }

    @Test
    void gapsUseTheBoundsAndHalfPaceDoublesThem() {
        FixedRandom r = new FixedRandom();
        assertThat(SenderPolicy.gapSeconds(false, false, s, r)).isEqualTo(45);
        assertThat(SenderPolicy.gapSeconds(true, false, s, r)).isEqualTo(15);
        assertThat(SenderPolicy.gapSeconds(false, true, s, r)).isEqualTo(90);
        assertThat(SenderPolicy.gapSeconds(true, true, s, r)).isEqualTo(30);
        r.max = true;
        assertThat(SenderPolicy.gapSeconds(false, false, s, r)).isEqualTo(180);
        assertThat(SenderPolicy.gapSeconds(false, true, s, r)).isEqualTo(360);
    }

    @Test
    void windowIsWorkingDaysAndHoursInTheZone() {
        assertThat(SenderPolicy.inWindow(s, Instant.parse("2026-10-21T04:29:00Z"))).isFalse();
        assertThat(SenderPolicy.inWindow(s, Instant.parse("2026-10-21T04:30:00Z"))).isTrue();
        assertThat(SenderPolicy.inWindow(s, Instant.parse("2026-10-21T12:30:00Z"))).isFalse();
        assertThat(SenderPolicy.inWindow(s, Instant.parse("2026-10-25T05:30:00Z"))).isFalse();
    }

    @Test
    void dayBoundariesAreLocal() {
        assertThat(SenderPolicy.today(s, Instant.parse("2026-10-21T19:00:00Z"))).isEqualTo(LocalDate.of(2026, 10, 22));
        assertThat(SenderPolicy.endOfDay(s, WED_11_IST)).isEqualTo(Instant.parse("2026-10-21T18:30:00Z"));
        assertThat(SenderPolicy.startOfDay(s, WED_11_IST)).isEqualTo(Instant.parse("2026-10-20T18:30:00Z"));
        assertThat(SenderPolicy.dayStartsAt(s, LocalDate.of(2026, 10, 21), 45))
                .isEqualTo(Instant.parse("2026-10-21T05:15:00Z"));
    }
}
