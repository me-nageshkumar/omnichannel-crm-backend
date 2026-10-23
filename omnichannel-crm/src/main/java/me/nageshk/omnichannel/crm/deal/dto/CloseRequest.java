package me.nageshk.omnichannel.crm.deal.dto;

/** {@code outcome} WON or LOST; {@code lostReason} (LOST only) an active {@code lostReasons} key. */
public record CloseRequest(String outcome, String lostReason) {}
