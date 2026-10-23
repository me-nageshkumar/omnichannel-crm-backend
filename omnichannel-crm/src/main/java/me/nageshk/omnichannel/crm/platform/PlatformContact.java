package me.nageshk.omnichannel.crm.platform;

/** A brand contact as the CRM sees it. */
public record PlatformContact(String id, String workspaceId, String name, String phone) {}
