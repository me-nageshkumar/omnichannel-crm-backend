package me.nageshk.omnichannel.campaign;

import java.time.Instant;
import me.nageshk.omnichannel.phone.PhoneNumberStatus;

/** A sender as the campaign pages show it. */
public record SenderView(String phoneNumberId, String number, String label, SenderState state, int ageDay,
        int capToday, long usedToday, long hourlyUsed, PhoneNumberStatus.Status phoneStatus, boolean ready,
        Instant pausedUntil, String pauseReason, Instant halfPaceUntil, boolean lowReplyRate, Instant restUntil,
        String runsOn, Instant logoutAt, Instant lastSendAt, Instant nextSendAt, Instant warmupStartedAt) {
}
