package me.nageshk.omnichannel.db.billing;

/** The supplier or the customer as printed on an invoice or credit note. */
public record DocumentParty(String legalName, String address, String stateCode, String stateName, String gstin) {}
