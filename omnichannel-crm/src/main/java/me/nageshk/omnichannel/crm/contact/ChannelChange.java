package me.nageshk.omnichannel.crm.contact;

import java.time.Instant;

public record ChannelChange(String fromPhoneNumberId, String toPhoneNumberId, String byUserId, Instant at,
        String reason) {}
