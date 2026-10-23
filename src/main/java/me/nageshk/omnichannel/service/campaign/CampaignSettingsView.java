package me.nageshk.omnichannel.service.campaign;

import java.time.DayOfWeek;
import java.util.List;
import me.nageshk.omnichannel.db.campaign.CampaignSettings;

public record CampaignSettingsView(String timezone, String defaultCountryCode, List<DayOfWeek> workingDays,
        String startTime, String endTime, int gapMinSeconds, int gapMaxSeconds, int skipGapMinSeconds,
        int skipGapMaxSeconds, int hourlyCap, List<CampaignSettings.WarmupStep> warmupCaps, int matureCap,
        int healthyCap, int restDays, int recontactDays, List<String> stopWords, Long version) {

    public static CampaignSettingsView of(CampaignSettings s) {
        return new CampaignSettingsView(s.getTimezone(), s.getDefaultCountryCode(), s.getWorkingDays(),
                s.getStartTime(), s.getEndTime(), s.getGapMinSeconds(), s.getGapMaxSeconds(),
                s.getSkipGapMinSeconds(), s.getSkipGapMaxSeconds(), s.getHourlyCap(), s.getWarmupCaps(),
                s.getMatureCap(), s.getHealthyCap(), s.getRestDays(), s.getRecontactDays(), s.getStopWords(),
                s.getVersion());
    }
}
