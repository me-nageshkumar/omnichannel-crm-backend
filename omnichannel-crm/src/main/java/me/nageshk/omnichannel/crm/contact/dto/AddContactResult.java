package me.nageshk.omnichannel.crm.contact.dto;

/** The CRM contact, and whether this call made it. */
public record AddContactResult(CrmContactView contact, boolean created) {}
