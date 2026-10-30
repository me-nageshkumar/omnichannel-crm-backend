package me.nageshk.omnichannel.crm.report.dto;

import java.util.List;

/** Spec C7: open now and the period's outcomes per owner; {@code ownerId} "" is "Unassigned". */
public record ByOwnerView(String currency, List<Row> rows, long otherCurrencyDeals) {

    public record Row(String ownerId, String label, long openCount, double openAmount, long wonCount,
            double wonAmount, long lostCount, Double winRate) {}
}
