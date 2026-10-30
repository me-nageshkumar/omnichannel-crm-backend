package me.nageshk.omnichannel.crm.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.bson.types.Decimal128;
import org.junit.jupiter.api.Test;

class ReportMoneyTest {

    @Test
    void currencyFallsBackToDefaultThenInr() {
        assertThat(ReportMoney.currencyOf("USD", "EUR")).isEqualTo("USD");
        assertThat(ReportMoney.currencyOf(" ", "EUR")).isEqualTo("EUR");
        assertThat(ReportMoney.currencyOf(null, null)).isEqualTo("INR");
    }

    @Test
    void weightedUsesDealThenStageProbability() {
        assertThat(ReportMoney.weighted(new BigDecimal("1000"), 25, 50)).isEqualByComparingTo("250.00");
        assertThat(ReportMoney.weighted(new BigDecimal("1000"), null, 50)).isEqualByComparingTo("500.00");
        assertThat(ReportMoney.weighted(new BigDecimal("1000"), null, null)).isEqualByComparingTo("0.00");
    }

    @Test
    void weightedOfMissingAmountIsZero() {
        assertThat(ReportMoney.weighted(null, 80, 10)).isEqualByComparingTo("0.00");
    }

    @Test
    void scaledRoundsHalfUp() {
        assertThat(ReportMoney.scaled(new BigDecimal("12.345"))).isEqualTo(12.35);
        assertThat(ReportMoney.scaled(null)).isEqualTo(0.0);
        assertThat(ReportMoney.toBigDecimal(new Decimal128(new BigDecimal("7.5")))).isEqualByComparingTo("7.5");
        assertThat(ReportMoney.toBigDecimal(3L)).isEqualByComparingTo("3");
        assertThat(ReportMoney.toBigDecimal(null)).isEqualByComparingTo("0");
    }
}
