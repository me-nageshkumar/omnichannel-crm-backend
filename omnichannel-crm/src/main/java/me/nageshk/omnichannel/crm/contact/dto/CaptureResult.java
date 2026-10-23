package me.nageshk.omnichannel.crm.contact.dto;

import me.nageshk.omnichannel.crm.deal.dto.DealView;

/** The CRM contact and its open deal in the chosen pipeline, and whether each was made by this call. */
public record CaptureResult(CrmContactView contact, DealView deal, boolean createdContact, boolean createdDeal) {}
