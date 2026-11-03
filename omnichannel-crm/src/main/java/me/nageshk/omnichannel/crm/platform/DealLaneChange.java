package me.nageshk.omnichannel.crm.platform;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A deal that moved to another lane (stage or pipeline). {@code contactId} is the primary CRM contact's brand contact
 * id, {@code phoneNumberId} the deal's channel account; {@code from.name} is null for a removed stage;
 * {@code pipeline*} is the pipeline the deal is in now.
 */
public record DealLaneChange(String workspaceId, String customerId, String dealId, String title, String status,
        BigDecimal amount, String currency, String ownerId, String lostReason, String pipelineId,
        String pipelineName, Lane from, Lane to, String contactId, String phoneNumberId, Instant at) {

    public record Lane(String id, String name, String type) {
    }
}
