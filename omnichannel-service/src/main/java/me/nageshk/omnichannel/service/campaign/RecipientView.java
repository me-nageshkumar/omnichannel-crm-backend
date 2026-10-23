package me.nageshk.omnichannel.service.campaign;

import java.time.Instant;
import me.nageshk.omnichannel.db.campaign.RecipientStatus;
import me.nageshk.omnichannel.db.campaign.SkipReason;

/** A recipient row as the campaign page shows it. */
public record RecipientView(String id, String phone, String contactId, String contactName, RecipientStatus status,
        SkipReason skipReason, String senderId, String senderNumber, String templateId, Instant attemptedAt,
        Instant sentAt, Instant deliveredAt, Instant readAt, Instant repliedAt, String error, int attempts,
        Instant dispatchedAt) {
}
