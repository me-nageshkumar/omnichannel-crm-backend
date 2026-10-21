package me.nageshk.omnichannel.campaign;

import java.time.Instant;

/** A recipient row as the campaign page shows it. */
public record RecipientView(String id, String phone, String contactId, String contactName, RecipientStatus status,
        SkipReason skipReason, String senderId, String senderNumber, String templateId, Instant attemptedAt,
        Instant sentAt, Instant deliveredAt, Instant readAt, Instant repliedAt, String error, int attempts,
        Instant dispatchedAt) {
}
