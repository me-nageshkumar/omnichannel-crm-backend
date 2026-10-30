package me.nageshk.omnichannel.crm.report.dto;

import java.util.List;

/** Spec C9: deals per WhatsApp number — created and won in the period, open amount now. */
public record ByNumberView(String currency, List<Row> rows, long otherCurrencyDeals) {

    public record Row(String phoneNumberId, String label, long created, long won, double wonAmount,
            double openAmount) {}
}
