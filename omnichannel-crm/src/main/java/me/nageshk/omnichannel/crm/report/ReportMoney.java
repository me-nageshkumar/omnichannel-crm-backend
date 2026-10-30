package me.nageshk.omnichannel.crm.report;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.bson.types.Decimal128;

/** Currency and weighted-value rules of the reports (spec C4). */
public final class ReportMoney {

    /** Used when neither the deal nor the settings name a currency. */
    static final String FALLBACK_CURRENCY = "INR";

    private ReportMoney() {}

    /** A deal's reporting currency: its own, else the settings' default, else INR. */
    public static String currencyOf(String dealCurrency, String defaultCurrency) {
        if (dealCurrency != null && !dealCurrency.isBlank()) return dealCurrency.trim();
        return defaultCurrency != null && !defaultCurrency.isBlank() ? defaultCurrency.trim() : FALLBACK_CURRENCY;
    }

    /** A numeric aggregation result (Decimal128, Integer, Long, Double or null) as a BigDecimal; null → 0. */
    public static BigDecimal toBigDecimal(Object value) {
        if (value == null) return BigDecimal.ZERO;
        if (value instanceof Decimal128 d) return d.bigDecimalValue();
        if (value instanceof BigDecimal b) return b;
        return new BigDecimal(value.toString());
    }

    /** amount × probability / 100 with the deal's probability, else the stage's, else 0; a missing amount is 0. */
    public static BigDecimal weighted(BigDecimal amount, Integer dealProbability, Integer stageProbability) {
        int probability = dealProbability != null ? dealProbability : stageProbability != null ? stageProbability : 0;
        BigDecimal value = amount == null ? BigDecimal.ZERO : amount;
        return value.multiply(BigDecimal.valueOf(probability)).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }

    /** The JSON number for an amount: 2 decimals, half up; null → 0. */
    public static double scaled(BigDecimal value) {
        return value == null ? 0.0 : value.setScale(2, RoundingMode.HALF_UP).doubleValue();
    }
}
