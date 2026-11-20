package me.nageshk.omnichannel.service.campaign;

import java.time.Instant;

/**
 * A Cloud number as the campaign wizard and detail show it (spec 2026-11-21 §5.8); {@code dailyLimit} null means
 * unlimited, {@code heldUntil} is set while Meta asked it (or its WhatsApp account) to wait.
 */
public record CloudSenderView(String phoneNumberId, String number, String label, String wabaId, boolean canSend,
        String qualityRating, String messagingLimitTier, Integer dailyLimit, int usedLast24h, Instant heldUntil) {
}
