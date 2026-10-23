package me.nageshk.omnichannel.crm.company.dto;

public record CompanySearchRequest(String q, String ownerId, String industry, String sort, String dir, Integer page,
        Integer size) {}
