package me.nageshk.omnichannel.crm.deal.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Create or edit a deal. On edit, absent (null) leaves a field unchanged and a blank string clears it; {@code version}
 * is required. {@code stageId} is honoured on create only (moves go through {@code /move}).
 */
public record DealRequest(String title, String pipelineId, String stageId, BigDecimal amount, String currency,
        Integer probability, Boolean probabilityOverridden, String expectedCloseDate, String companyId,
        String primaryCrmContactId, List<String> crmContactIds, String ownerId, String teamId, String source,
        List<String> labelIds, Map<String, Object> customFields, Long version) {}
