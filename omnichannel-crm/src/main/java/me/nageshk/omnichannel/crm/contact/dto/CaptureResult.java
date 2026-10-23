package me.nageshk.omnichannel.crm.contact.dto;

/** Task 10 adds the deal and whether it was created. */
public record CaptureResult(CrmContactView contact, boolean createdContact) {}
