package me.nageshk.omnichannel.crm.deal.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import me.nageshk.omnichannel.crm.common.Ref;
import me.nageshk.omnichannel.crm.contact.Channel;
import me.nageshk.omnichannel.crm.deal.DealStatus;

/** A deal as the API shows it; {@code overdue} is {@code nextTaskDueAt < now}. */
public record DealView(String id, String title, String pipelineId, String stageId, double position,
        DealStatus status, BigDecimal amount, String currency, Integer probability, boolean probabilityOverridden,
        String expectedCloseDate, Instant closedAt, String lostReason, Ref company, PrimaryContact primaryContact,
        List<String> crmContactIds, Ref owner, String source, List<String> labelIds,
        Map<String, Object> customFields, Channel channel, Instant stageEnteredAt, boolean rotting, boolean overdue,
        Instant lastActivityAt, Instant nextTaskDueAt, Instant creationTime, Long version) {

    public record PrimaryContact(String id, String name, String phone) {}
}
