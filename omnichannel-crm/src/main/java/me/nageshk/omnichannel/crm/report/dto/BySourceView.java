package me.nageshk.omnichannel.crm.report.dto;

import java.util.List;
import me.nageshk.omnichannel.crm.contact.OriginKind;

/** Spec C8: deals per source in the period, and contacts per origin created in the period. */
public record BySourceView(String currency, List<Deal> deals, List<Contact> contacts, long otherCurrencyDeals) {

    public record Deal(String source, String label, long created, long won, double wonAmount) {}

    public record Contact(OriginKind originKind, long created) {}
}
