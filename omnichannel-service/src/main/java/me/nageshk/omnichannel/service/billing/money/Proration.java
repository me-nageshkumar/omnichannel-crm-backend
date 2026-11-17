package me.nageshk.omnichannel.service.billing.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/** A share of an amount by days: what part of a term is left, or what part of what was paid is unused. */
public final class Proration {

    private Proration() {}

    /** Days from the first date up to, not including, the second. */
    public static long days(LocalDate from, LocalDate to) {
        return ChronoUnit.DAYS.between(from, to);
    }

    /** {@code amount × numerator / denominator}: nothing when no days are left, the whole when all are. */
    public static BigDecimal part(BigDecimal amount, long numeratorDays, long denominatorDays) {
        if (numeratorDays <= 0 || denominatorDays <= 0) return Money.ZERO;
        if (numeratorDays >= denominatorDays) return Money.round(amount);
        return amount.multiply(BigDecimal.valueOf(numeratorDays))
                .divide(BigDecimal.valueOf(denominatorDays), 2, RoundingMode.HALF_UP);
    }
}
