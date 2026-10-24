package me.nageshk.omnichannel.crm.deal.dto;

/** {@code sort} is a whitelisted field, optionally {@code "field,asc|desc"}; {@code dir} may give the direction too. */
public record DealSearchRequest(String pipelineId, String stageId, String status, String ownerId, String companyId,
        String crmContactId, String q, String sort, String dir, Integer page, Integer size) {}
