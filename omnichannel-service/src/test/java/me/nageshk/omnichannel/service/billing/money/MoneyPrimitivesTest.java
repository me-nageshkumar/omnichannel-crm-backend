package me.nageshk.omnichannel.service.billing.money;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import me.nageshk.omnichannel.db.billing.TaxAmounts;
import org.junit.jupiter.api.Test;

class MoneyPrimitivesTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private static BigDecimal bd(String s) {
        return new BigDecimal(s);
    }

    @Test
    void taxWithinAStateIsSplitInTwo() {
        TaxAmounts t = Gst.of(bd("900.00"), bd("18"), false);
        assertThat(t.cgst()).isEqualByComparingTo("81.00");
        assertThat(t.sgst()).isEqualByComparingTo("81.00");
        assertThat(t.igst()).isEqualByComparingTo("0");
        assertThat(t.interState()).isFalse();
        assertThat(t.ratePercent()).isEqualByComparingTo("18");
        assertThat(t.total()).isEqualByComparingTo("162.00");
    }

    @Test
    void taxBetweenStatesIsOneAmount() {
        TaxAmounts t = Gst.of(bd("900.00"), bd("18"), true);
        assertThat(t.igst()).isEqualByComparingTo("162.00");
        assertThat(t.cgst()).isEqualByComparingTo("0");
        assertThat(t.sgst()).isEqualByComparingTo("0");
        assertThat(t.interState()).isTrue();
    }

    @Test
    void anOddPaisaGoesToCgst() {
        TaxAmounts odd = Gst.of(bd("100.03"), bd("18"), false); // 18.0054 → 18.01
        assertThat(odd.cgst()).isEqualByComparingTo("9.01");
        assertThat(odd.sgst()).isEqualByComparingTo("9.00");
        TaxAmounts credit = Gst.of(bd("464.52"), bd("18"), false); // 83.6136 → 83.61
        assertThat(credit.cgst()).isEqualByComparingTo("41.81");
        assertThat(credit.sgst()).isEqualByComparingTo("41.80");
        TaxAmounts addOn = Gst.of(bd("203.23"), bd("18"), false); // 36.5814 → 36.58
        assertThat(addOn.cgst()).isEqualByComparingTo("18.29");
        assertThat(addOn.sgst()).isEqualByComparingTo("18.29");
        assertThat(Gst.of(bd("0.00"), bd("18"), false).total()).isEqualByComparingTo("0");
    }

    @Test
    void statesDecideWhetherTaxIsBetweenStates() {
        assertThat(Gst.interState("29", "29")).isFalse();
        assertThat(Gst.interState("29", "27")).isTrue();
    }

    @Test
    void theFinancialYearTurnsOnTheFirstOfAprilInTheBillingZone() {
        assertThat(FinancialYear.of(Instant.parse("2027-03-31T18:29:59Z"), IST)).isEqualTo("2026-27");
        assertThat(FinancialYear.of(Instant.parse("2027-03-31T18:30:00Z"), IST)).isEqualTo("2027-28");
        assertThat(FinancialYear.of(Instant.parse("2026-12-01T05:00:00Z"), IST)).isEqualTo("2026-27");
        assertThat(FinancialYear.of(Instant.parse("2099-06-01T00:00:00Z"), IST)).isEqualTo("2099-00");
    }

    @Test
    void amountsAreSpelledTheIndianWay() {
        assertThat(AmountInWords.rupees(bd("1062.00"))).isEqualTo("Rupees One Thousand Sixty-Two Only");
        assertThat(AmountInWords.rupees(bd("239.81"))).isEqualTo("Rupees Two Hundred Thirty-Nine and Eighty-One Paise Only");
        assertThat(AmountInWords.rupees(bd("100000"))).isEqualTo("Rupees One Lakh Only");
        assertThat(AmountInWords.rupees(bd("12345678.50"))).isEqualTo(
                "Rupees One Crore Twenty-Three Lakh Forty-Five Thousand Six Hundred Seventy-Eight and Fifty Paise Only");
        assertThat(AmountInWords.rupees(bd("0.00"))).isEqualTo("Rupees Zero Only");
        assertThat(AmountInWords.rupees(bd("0.05"))).isEqualTo("Rupees Zero and Five Paise Only");
        assertThat(AmountInWords.rupees(bd("19"))).isEqualTo("Rupees Nineteen Only");
        assertThat(AmountInWords.rupees(bd("1000000000"))).isEqualTo("Rupees One Hundred Crore Only");
        assertThat(AmountInWords.rupees(bd("110"))).isEqualTo("Rupees One Hundred Ten Only");
    }

    @Test
    void aPartOfAnAmountByDays() {
        assertThat(Proration.part(bd("300"), 21, 31)).isEqualByComparingTo("203.23");
        assertThat(Proration.part(bd("900"), 16, 31)).isEqualByComparingTo("464.52");
        assertThat(Proration.part(bd("203.23"), 16, 21)).isEqualByComparingTo("154.84");
        assertThat(Proration.part(bd("300"), 1, 31)).isEqualByComparingTo("9.68");
        assertThat(Proration.part(bd("300"), 0, 31)).isEqualByComparingTo("0.00");
        assertThat(Proration.part(bd("300"), -3, 31)).isEqualByComparingTo("0.00");
        assertThat(Proration.part(bd("300"), 31, 31)).isEqualByComparingTo("300.00");
        assertThat(Proration.part(bd("300"), 40, 31)).isEqualByComparingTo("300.00");
        assertThat(Proration.part(bd("300"), 5, 0)).isEqualByComparingTo("0.00");
        assertThat(Proration.days(LocalDate.of(2026, 12, 11), LocalDate.of(2027, 1, 1))).isEqualTo(21);
        assertThat(Proration.days(LocalDate.of(2028, 2, 1), LocalDate.of(2028, 3, 1))).isEqualTo(29);
    }

    @Test
    void moneyIsRoundedHalfUpToPaise() {
        assertThat(Money.round(bd("1.005"))).isEqualByComparingTo("1.01");
        assertThat(Money.round(bd("1.004"))).isEqualByComparingTo("1.00");
        assertThat(Money.round(bd("5")).scale()).isEqualTo(2);
        assertThat(Money.percentOff(bd("900.00"), bd("50"))).isEqualByComparingTo("450.00");
        assertThat(Money.percentOff(bd("203.23"), bd("12.5"))).isEqualByComparingTo("177.83"); // 177.826
        assertThat(Money.percentOff(bd("900.00"), null)).isEqualByComparingTo("900.00");
        assertThat(Money.percentOff(bd("900.00"), bd("100"))).isEqualByComparingTo("0.00");
    }
}
