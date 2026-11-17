package me.nageshk.omnichannel.app.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import me.nageshk.omnichannel.common.billing.LimitKey;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class OrdersApiIT extends MoneyApiTestBase {

    @Test
    void aQuoteAndTheOrderRaisedFromItAgree() throws Exception {
        JsonNode quote = json(POST(quotesPath(acme), amToken, request("NEW", "gold-monthly")).andExpect(status().isOk()));
        assertThat(quote.get("taxable").decimalValue()).isEqualByComparingTo("900.00");
        assertThat(quote.at("/tax/cgst").decimalValue()).isEqualByComparingTo("81.00");
        assertThat(quote.at("/tax/sgst").decimalValue()).isEqualByComparingTo("81.00");
        assertThat(quote.get("total").decimalValue()).isEqualByComparingTo("1062.00");
        assertThat(quote.get("payable").decimalValue()).isEqualByComparingTo("1062.00");
        assertThat(quote.get("planName").asString()).isEqualTo("Gold");
        assertThat(quote.get("period").asString()).isEqualTo("MONTHLY");
        assertThat(Instant.parse(quote.get("termStart").asString())).isEqualTo(Instant.parse("2026-11-30T18:30:00Z"));
        assertThat(Instant.parse(quote.get("termEnd").asString())).isEqualTo(Instant.parse("2026-12-31T18:30:00Z"));
        assertThat(quote.at("/lines/0/description").asString()).isEqualTo("Gold, monthly (1 Dec 2026 – 31 Dec 2026)");

        JsonNode order = raise(acme, request("NEW", "gold-monthly"));
        assertThat(order.get("number").asString()).isEqualTo("ORD-000001");
        assertThat(order.get("status").asString()).isEqualTo("REQUESTED");
        assertThat(order.get("kind").asString()).isEqualTo("NEW");
        assertThat(order.get("total").decimalValue()).isEqualByComparingTo(quote.get("total").decimalValue());
        assertThat(order.get("customerName").asString()).isEqualTo("Acme");
        assertThat(order.get("raisedByStaff").asBoolean()).isTrue();
        assertThat(order.get("raisedByName").asString()).isNotBlank();
        assertThat(Instant.parse(order.get("validUntil").asString())).isEqualTo(DEC_1.plusSeconds(7 * 86400));
        assertThat(order.get("payment").isNull()).isTrue();

        GET("/api/billing/orders/" + order.get("id").asString(), amToken).andExpect(status().isOk())
                .andExpect(jsonPath("$.number").value("ORD-000001"));
        GET(ordersPath(acme), amToken).andExpect(jsonPath("$.length()").value(1));
        // nothing happened to the customer yet
        GET("/api/billing/customers/" + acme.getId() + "/subscription", amToken).andExpect(jsonPath("$.state").value("NONE"));
    }

    @Test
    void oneOpenOrderMayChangeThePlanAtATime() throws Exception {
        billing.assign(acme.getId(), Set.of("crm"), Map.of(LimitKey.USERS, 5));
        raise(acme, request("CHANGE", "platinum-monthly"));
        POST(ordersPath(acme), amToken, request("CHANGE", "gold-monthly")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_PENDING"));
        // another customer is not affected
        raise(bolt, request("NEW", "gold-monthly"));
    }

    @Test
    void billingMustBeSetUpFirst() throws Exception {
        me.nageshk.omnichannel.db.tenant.Customer fresh = testUsers.customer("Fresh");
        POST(ordersPath(fresh), amToken, request("NEW", "gold-monthly")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BILLING_NOT_SET_UP"))
                .andExpect(jsonPath("$.missing").value("customer billing profile"));
        POST(quotesPath(fresh), amToken, request("NEW", "gold-monthly")).andExpect(status().isConflict());

        billing.reset();
        POST(ordersPath(acme), amToken, request("NEW", "gold-monthly")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.missing").value("supplier details in billing settings"));
    }

    @Test
    void anExtraDiscountNeedsAReason() throws Exception {
        Map<String, Object> half = request("NEW", "gold-monthly");
        half.put("extraDiscountPercent", 50);
        POST(ordersPath(acme), amToken, half).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.extraDiscountReason").exists());
        half.put("extraDiscountReason", "Launch offer");
        JsonNode order = raise(acme, half);
        assertThat(order.get("total").decimalValue()).isEqualByComparingTo("531.00");
        assertThat(order.get("extraDiscountReason").asString()).isEqualTo("Launch offer");
    }

    @Test
    void anOrderIsCancelledOrRejectedOnce() throws Exception {
        String a = raise(acme, request("NEW", "gold-monthly")).get("id").asString();
        POST("/api/billing/orders/" + a + "/cancel", amToken, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        POST("/api/billing/orders/" + a + "/cancel", amToken, null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_OPEN"));

        String b = raise(acme, request("NEW", "gold-monthly")).get("id").asString();
        POST("/api/billing/orders/" + b + "/reject", amToken, Map.of("reason", " ")).andExpect(status().isBadRequest());
        POST("/api/billing/orders/" + b + "/reject", amToken, Map.of("reason", "Wrong plan")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED")).andExpect(jsonPath("$.rejectedReason").value("Wrong plan"));
        POST("/api/billing/orders/" + b + "/reject", amToken, Map.of("reason", "Again")).andExpect(status().isConflict());
        GET("/api/billing/orders/000000000000000000000000", amToken).andExpect(status().isNotFound());
    }

    @Test
    void anOrderPastItsValidityReadsAsExpired() throws Exception {
        String id = raise(acme, request("NEW", "gold-monthly")).get("id").asString();
        on(day(2026, 12, 9));
        GET("/api/billing/orders/" + id, amToken).andExpect(jsonPath("$.status").value("EXPIRED"));
        POST("/api/billing/orders/" + id + "/cancel", amToken, null).andExpect(status().isConflict());
        // and it no longer blocks a new one
        raise(acme, request("NEW", "gold-monthly"));
    }

    @Test
    void theQueueListsOpenOrdersOldestFirst() throws Exception {
        raise(acme, request("NEW", "gold-monthly"));
        clock.advance(java.time.Duration.ofMinutes(5));
        raise(bolt, request("NEW", "gold-monthly"));
        String cancelled = raise(testCustomer("Cora"), request("NEW", "gold-monthly")).get("id").asString();
        POST("/api/billing/orders/" + cancelled + "/cancel", amToken, null).andExpect(status().isOk());

        JsonNode open = json(GET("/api/billing/orders?status=REQUESTED", amToken).andExpect(status().isOk()));
        assertThat(open.get("total").asInt()).isEqualTo(2);
        assertThat(open.at("/items/0/customerName").asString()).isEqualTo("Acme");
        assertThat(open.at("/items/1/customerName").asString()).isEqualTo("Bolt");
        JsonNode all = json(GET("/api/billing/orders", amToken).andExpect(status().isOk()));
        assertThat(all.get("total").asInt()).isEqualTo(3);
    }

    private me.nageshk.omnichannel.db.tenant.Customer testCustomer(String name) throws Exception {
        me.nageshk.omnichannel.db.tenant.Customer c = testUsers.customer(name);
        PUT(profilePath(c), amToken, profile(name + " Pvt Ltd", "1 Road", "29")).andExpect(status().isOk());
        return c;
    }

    @Test
    void customersHaveNoWayIntoTheStaffPaths() throws Exception {
        POST(ordersPath(acme), adminToken, request("NEW", "gold-monthly")).andExpect(status().isForbidden());
        POST(quotesPath(acme), adminToken, request("NEW", "gold-monthly")).andExpect(status().isForbidden());
        GET("/api/billing/orders", adminToken).andExpect(status().isForbidden());
        GET(ordersPath(acme), adminToken).andExpect(status().isForbidden());
    }
}
