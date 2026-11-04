package me.nageshk.omnichannel.crm.platform;

/** The click-to-WhatsApp ad a chat started from, as the CRM sees it. */
public record PlatformAdClick(String sourceId, String sourceUrl, String sourceApp, String headline, String clickId) {}
