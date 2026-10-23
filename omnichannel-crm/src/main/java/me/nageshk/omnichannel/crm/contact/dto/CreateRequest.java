package me.nageshk.omnichannel.crm.contact.dto;

public record CreateRequest(String phone, String phoneNumberId, String firstName, String lastName,
        String pipelineId, String dealTitle) {}
