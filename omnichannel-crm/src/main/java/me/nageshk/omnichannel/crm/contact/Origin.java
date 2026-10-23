package me.nageshk.omnichannel.crm.contact;

import java.time.Instant;

/** How a CRM contact came to be; set once. */
public record Origin(OriginKind kind, String phoneNumberId, String chatId, Instant firstMessageAt,
        String capturedById) {}
