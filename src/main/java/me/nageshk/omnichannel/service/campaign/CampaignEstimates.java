package me.nageshk.omnichannel.service.campaign;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import me.nageshk.omnichannel.db.campaign.CampaignSettings;

/** How many working days the remaining messages take. */
final class CampaignEstimates {

    private static final int HORIZON_DAYS = 3660;

    private CampaignEstimates() {
    }

    static Estimate of(int remaining, int perDay, CampaignSettings s, Instant now) {
        if (remaining <= 0) return new Estimate(remaining, perDay, 0, null);
        if (perDay <= 0) return new Estimate(remaining, perDay, null, null);
        int days = (remaining + perDay - 1) / perDay;
        LocalDate today = SenderPolicy.today(s, now);
        LocalTime time = now.atZone(SenderPolicy.zone(s)).toLocalTime();
        boolean todayOpen = time.isBefore(LocalTime.parse(s.getEndTime()));
        int counted = 0;
        for (int i = 0; i < HORIZON_DAYS; i++) {
            LocalDate day = today.plusDays(i);
            if (!s.getWorkingDays().contains(day.getDayOfWeek())) continue;
            if (i == 0 && !todayOpen) continue;
            if (++counted == days) return new Estimate(remaining, perDay, days, day);
        }
        return new Estimate(remaining, perDay, days, null);
    }
}
