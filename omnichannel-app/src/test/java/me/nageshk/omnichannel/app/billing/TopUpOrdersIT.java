package me.nageshk.omnichannel.app.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.Map;
import me.nageshk.omnichannel.db.billing.Subscription;
import me.nageshk.omnichannel.db.billing.SubscriptionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/** A top-up is an order for money into the wallet: priced with GST, paid in full, and independent of the plan. */
class TopUpOrdersIT extends MoneyApiTestBase {

    @Autowired SubscriptionRepository subscriptions;

    @Test
    void aTopUpIsTheAmountPlusGstAndIsPaidInFull() throws Exception {
        buy(acme, request("NEW", "gold-monthly"));
        // account credit is for plans and add-ons, never for a top-up
        Subscription s = subscriptions.findByCustomerId(acme.getId()).orElseThrow();
        s.setCreditBalance(new BigDecimal("500.00"));
        subscriptions.save(s);

        JsonNode q = json(POST(quotesPath(acme), amToken, topUp(1000)).andExpect(status().isOk()));
        assertThat(q.get("kind").asString()).isEqualTo("TOPUP");
        assertThat(q.at("/lines/0/type").asString()).isEqualTo("TOPUP");
        assertThat(q.at("/lines/0/description").asString()).isEqualTo("Wallet top-up");
        assertThat(q.get("taxable").decimalValue()).isEqualByComparingTo("1000.00");
        assertThat(q.at("/tax/cgst").decimalValue()).isEqualByComparingTo("90.00");
        assertThat(q.at("/tax/sgst").decimalValue()).isEqualByComparingTo("90.00");
        assertThat(q.get("total").decimalValue()).isEqualByComparingTo("1180.00");
        assertThat(q.get("accountCreditUsed").decimalValue()).isEqualByComparingTo("0");
        assertThat(q.get("payable").decimalValue()).isEqualByComparingTo("1180.00");

        JsonNode order = raise(acme, topUp(1000));
        assertThat(order.get("payable").decimalValue()).isEqualByComparingTo("1180.00");
        assertThat(order.get("status").asString()).isEqualTo("REQUESTED");
    }

    @Test
    void aCustomerInAnotherStatePaysIgstOnItsTopUp() throws Exception {
        buy(bolt, request("NEW", "gold-monthly"));
        POST(quotesPath(bolt), amToken, topUp(1000)).andExpect(status().isOk())
                .andExpect(jsonPath("$.tax.igst").value(180.00)).andExpect(jsonPath("$.total").value(1180.00));
    }

    @Test
    void aTopUpThatCannotBeIsRefusedOnItsField() throws Exception {
        POST(quotesPath(acme), amToken, topUp(1000)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.kind").exists());
        buy(acme, request("NEW", "gold-monthly"));
        POST(quotesPath(acme), amToken, topUp(99.99)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.topUpAmount").exists());
        POST(quotesPath(acme), amToken, topUp(1000000.01)).andExpect(status().isBadRequest());
        POST(quotesPath(acme), amToken, topUp(100.005)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.topUpAmount").value("at most 2 decimals"));
        POST(quotesPath(acme), amToken, request("TOPUP", null)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.topUpAmount").value("is required"));
        Map<String, Object> discounted = topUp(1000);
        discounted.put("extraDiscountPercent", 10);
        discounted.put("extraDiscountReason", "no");
        POST(quotesPath(acme), amToken, discounted).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.extraDiscountPercent").exists());
        POST(quotesPath(acme), amToken, topUp(100)).andExpect(status().isOk());
        POST(quotesPath(acme), amToken, topUp(1000000)).andExpect(status().isOk());
    }

    @Test
    void topUpsWaitSideBySideAndSurviveAChangeOfPlan() throws Exception {
        buy(acme, request("NEW", "gold-monthly"));
        String staffs = raise(acme, topUp(1000)).get("id").asString();
        JsonNode mine = json(POST("/api/my/billing/orders", adminToken, topUp(500)).andExpect(status().isCreated()));
        assertThat(mine.get("raisedByStaff").asBoolean()).isFalse();
        // a plan change beside them is not held up by them, and does not put them out of date
        on(day(2026, 12, 5));
        buy(acme, request("UPGRADE", "platinum-monthly"));
        GET("/api/billing/orders/" + staffs, amToken).andExpect(jsonPath("$.status").value("REQUESTED"));
        GET("/api/billing/orders/" + mine.get("id").asString(), amToken).andExpect(jsonPath("$.status").value("REQUESTED"));
    }

    @Test
    void theOptionsSayWhetherAndHowMuchCanBeToppedUp() throws Exception {
        JsonNode none = json(GET("/api/my/billing/options", adminToken).andExpect(status().isOk()));
        assertThat(none.at("/topUp/allowed").asBoolean()).isFalse();
        assertThat(none.get("messageRate").isNull()).isTrue();

        buy(acme, request("NEW", "gold-monthly"));
        JsonNode gold = json(GET("/api/my/billing/options", adminToken));
        assertThat(gold.at("/topUp/allowed").asBoolean()).isTrue();
        assertThat(gold.at("/topUp/min").decimalValue()).isEqualByComparingTo("100");
        assertThat(gold.at("/topUp/max").decimalValue()).isEqualByComparingTo("1000000");
        assertThat(gold.get("messageRate").decimalValue()).isEqualByComparingTo("0.25");
        GET("/api/billing/customers/" + acme.getId() + "/wallet", amToken).andExpect(jsonPath("$.messageRate").value(0.25));

        // Platinum has no message rate: nothing is charged, so there is nothing to top up for
        on(day(2026, 12, 16));
        buy(acme, request("UPGRADE", "platinum-monthly"));
        JsonNode platinum = json(GET("/api/my/billing/options", adminToken));
        assertThat(platinum.at("/topUp/allowed").asBoolean()).isFalse();
        assertThat(platinum.at("/topUp/reason").asString()).isEqualTo("Messages are not charged on this plan");
    }
}
