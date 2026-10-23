package me.nageshk.omnichannel.crm.contact.dto;

public record ContactSearchRequest(String q, String ownerId, String lifecycleStatus, String companyId,
        String pipelineId, String sort, String dir, Integer page, Integer size) {}
