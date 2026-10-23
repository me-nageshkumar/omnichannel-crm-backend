package me.nageshk.omnichannel.crm.contact.dto;

/** Absent (null) leaves a field unchanged; blank clears it. */
public record ContactUpdateRequest(Long version, String firstName, String lastName, String email, String jobTitle,
        String ownerId, String lifecycleStatus, String source, String companyId) {}
