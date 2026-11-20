package me.nageshk.omnichannel.meta.api;

import java.time.Instant;
import tools.jackson.databind.JsonNode;

/**
 * One verified {@code messages} change of Meta's webhook, routed to our number: {@code value} is Meta's JSON exactly
 * as it came, {@code displayDigits} the number's digits.
 */
public record CloudChange(String connectionId, String customerId, String workspaceId, String phoneNumberId,
        String displayDigits, String wabaId, String field, JsonNode value, Instant receivedAt) {}
