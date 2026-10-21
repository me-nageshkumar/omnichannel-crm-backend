package me.nageshk.omnichannel.campaign;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

/** The arithmetic of warm-up, caps, gaps and the sending window; pure, so it needs no database. */
final class SenderPolicy {

    static final int ACTIVE_FROM_DAY = 31;

    private SenderPolicy() {
    }

    /** The sender's age in sending days, counting today. */
    static int ageDay(int sendingDays, LocalDate lastSendingDay, LocalDate today) {
        return sendingDays - (today.equals(lastSendingDay) ? 1 : 0) + 1;
    }

    static int cap(int ageDay, boolean lowReplyRate, CampaignSettings s) {
        int base;
        if (ageDay >= ACTIVE_FROM_DAY) {
            base = lowReplyRate ? s.getMatureCap() : s.getHealthyCap();
        } else {
            base = s.getWarmupCaps().get(0).cap();
            for (CampaignSettings.WarmupStep step : s.getWarmupCaps())
                if (step.fromDay() <= ageDay) base = step.cap();
        }
        return lowReplyRate ? Math.max(1, base / 2) : base;
    }

    static int gapSeconds(boolean skip, boolean halfPace, CampaignSettings s, PacingRandom r) {
        int min = skip ? s.getSkipGapMinSeconds() : s.getGapMinSeconds();
        int max = skip ? s.getSkipGapMaxSeconds() : s.getGapMaxSeconds();
        int factor = halfPace ? 2 : 1;
        return r.seconds(min * factor, max * factor);
    }

    static ZoneId zone(CampaignSettings s) {
        return ZoneId.of(s.getTimezone());
    }

    static LocalDate today(CampaignSettings s, Instant now) {
        return now.atZone(zone(s)).toLocalDate();
    }

    /** The next local midnight. */
    static Instant endOfDay(CampaignSettings s, Instant now) {
        return today(s, now).plusDays(1).atStartOfDay(zone(s)).toInstant();
    }

    /** The last local midnight. */
    static Instant startOfDay(CampaignSettings s, Instant now) {
        return today(s, now).atStartOfDay(zone(s)).toInstant();
    }

    static boolean inWindow(CampaignSettings s, Instant now) {
        var local = now.atZone(zone(s));
        if (!s.getWorkingDays().contains(local.getDayOfWeek())) return false;
        LocalTime time = local.toLocalTime();
        return !time.isBefore(LocalTime.parse(s.getStartTime())) && time.isBefore(LocalTime.parse(s.getEndTime()));
    }

    /** When sending starts on {@code day}: the window opens, plus the sender's random offset. */
    static Instant dayStartsAt(CampaignSettings s, LocalDate day, int offsetMinutes) {
        return day.atTime(LocalTime.parse(s.getStartTime())).plusMinutes(offsetMinutes).atZone(zone(s)).toInstant();
    }
}
