package me.nageshk.omnichannel.crm.contact.dto;

/** A channel contact of the brand and the channel account its CRM contact sits on. */
public record AddContactRequest(String contactId, String channelAccountId) {}
