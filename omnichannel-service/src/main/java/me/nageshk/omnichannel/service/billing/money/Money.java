package me.nageshk.omnichannel.service.billing.money;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Rupees at two decimals, halves rounded up. */
public final class Money {

    public static final BigDecimal ZERO = new BigDecimal("0.00");
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private Money() {}

    public static BigDecimal round(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP);
    }

    /** The amount less a percentage of it; a null percentage takes nothing off. */
    public static BigDecimal percentOff(BigDecimal amount, BigDecimal percent) {
        if (percent == null || percent.signum() == 0) return round(amount);
        return round(amount.multiply(HUNDRED.subtract(percent)).divide(HUNDRED, 10, RoundingMode.HALF_UP));
    }

    public static BigDecimal zeroIfNull(BigDecimal amount) {
        return amount == null ? ZERO : amount;
    }
}
