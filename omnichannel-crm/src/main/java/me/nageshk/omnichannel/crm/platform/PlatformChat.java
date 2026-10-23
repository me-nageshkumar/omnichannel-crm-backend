package me.nageshk.omnichannel.crm.platform;

import java.time.Instant;

/** A brand chat as the CRM sees it. */
public record PlatformChat(String id, String workspaceId, String phoneNumberId, String contactId,
        String assignedUserId, String assignedTeamId, Instant lastInboundAt) {}
