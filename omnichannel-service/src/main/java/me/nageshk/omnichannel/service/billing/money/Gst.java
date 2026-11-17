package me.nageshk.omnichannel.service.billing.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import me.nageshk.omnichannel.db.billing.TaxAmounts;

/** GST on a taxable value: IGST between states; within one, CGST and SGST, the odd paisa going to CGST. */
public final class Gst {

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal TWO = new BigDecimal("2");

    private Gst() {}

    public static TaxAmounts of(BigDecimal taxable, BigDecimal ratePercent, boolean interState) {
        BigDecimal tax = Money.round(taxable.multiply(ratePercent).divide(HUNDRED, 10, RoundingMode.HALF_UP));
        if (interState) return new TaxAmounts(ratePercent, Money.ZERO, Money.ZERO, tax, true);
        BigDecimal sgst = tax.divide(TWO, 2, RoundingMode.DOWN);
        return new TaxAmounts(ratePercent, tax.subtract(sgst), sgst, Money.ZERO, false);
    }

    /** Whether a supply from the supplier's state to the customer's is between states. */
    public static boolean interState(String supplierState, String customerState) {
        return supplierState == null || !supplierState.equals(customerState);
    }
}
