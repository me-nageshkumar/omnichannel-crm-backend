package me.nageshk.omnichannel.service.billing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import me.nageshk.omnichannel.db.billing.PlanItem;

/** A plan's price for one period, before tax: what its feature groups add up to, and that less the plan's discount. */
public final class PlanPricing {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    /** Rupees at two decimals. */
    public record Price(BigDecimal actual, BigDecimal discount, BigDecimal discounted) {}

    private PlanPricing() {}

    public static Price price(List<PlanItem> items, BigDecimal discountPercent) {
        BigDecimal actual = BigDecimal.ZERO;
        if (items != null) {
            for (PlanItem i : items) if (i != null && i.price() != null) actual = actual.add(i.price());
        }
        actual = money(actual);
        BigDecimal percent = discountPercent == null ? BigDecimal.ZERO : discountPercent;
        BigDecimal discounted = money(actual.multiply(HUNDRED.subtract(percent)).divide(HUNDRED, 10, RoundingMode.HALF_UP));
        return new Price(actual, actual.subtract(discounted), discounted);
    }

    /** An amount at two decimals, halves rounded up. */
    public static BigDecimal money(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP);
    }
}
