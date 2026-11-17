package me.nageshk.omnichannel.service.billing;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import me.nageshk.omnichannel.db.billing.PlanItem;
import me.nageshk.omnichannel.service.billing.PlanPricing.Price;
import org.junit.jupiter.api.Test;

class PlanPricingTest {

    @Test
    void thePriceIsTheSumLessTheDiscount() {
        Price p = PlanPricing.price(items("1000", "499.50"), new BigDecimal("10"));
        assertThat(p.actual()).isEqualByComparingTo("1499.50");
        assertThat(p.discount()).isEqualByComparingTo("149.95");
        assertThat(p.discounted()).isEqualByComparingTo("1349.55");
        assertThat(p.actual().scale()).isEqualTo(2);
        assertThat(p.discounted().scale()).isEqualTo(2);
    }

    @Test
    void theDiscountedPriceIsRoundedHalfUpToPaise() {
        // 999.99 × (100 − 33.33) / 100 = 666.693333
        assertThat(PlanPricing.price(items("999.99"), new BigDecimal("33.33")).discounted()).isEqualByComparingTo("666.69");
        // 10.05 × 0.5 = 5.025
        assertThat(PlanPricing.price(items("10.05"), new BigDecimal("50")).discounted()).isEqualByComparingTo("5.03");
    }

    @Test
    void nothingAndEverythingOff() {
        Price empty = PlanPricing.price(items(), new BigDecimal("50"));
        assertThat(empty.actual()).isEqualByComparingTo("0");
        assertThat(empty.discounted()).isEqualByComparingTo("0");
        assertThat(PlanPricing.price(items("100"), new BigDecimal("100")).discounted()).isEqualByComparingTo("0");
        assertThat(PlanPricing.price(items("100"), null).discounted()).isEqualByComparingTo("100");
    }

    private static List<PlanItem> items(String... prices) {
        List<PlanItem> out = new ArrayList<>();
        for (int i = 0; i < prices.length; i++) out.add(new PlanItem("g" + i, new BigDecimal(prices[i])));
        return out;
    }
}
