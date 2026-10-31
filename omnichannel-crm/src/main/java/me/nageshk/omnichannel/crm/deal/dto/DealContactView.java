package me.nageshk.omnichannel.crm.deal.dto;

/** One person on a deal, named for display; the primary contact comes first in a list of these. */
public record DealContactView(String id, String name, String phone, String jobTitle, boolean primary) {}
