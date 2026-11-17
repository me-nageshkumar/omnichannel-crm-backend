package me.nageshk.omnichannel.app.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import me.nageshk.omnichannel.common.billing.OrderStatus;
import me.nageshk.omnichannel.db.billing.BillingChange;
import me.nageshk.omnichannel.db.billing.BillingChangeRepository;
import me.nageshk.omnichannel.db.billing.BillingOrder;
import me.nageshk.omnichannel.db.billing.BillingOrderRepository;
import me.nageshk.omnichannel.db.billing.BillingSettings;
import me.nageshk.omnichannel.db.billing.Subscription;
import me.nageshk.omnichannel.db.billing.SubscriptionRepository;
import me.nageshk.omnichannel.service.billing.order.OrderActivator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/** The smaller things the whole-branch review found. */
class ReviewMinorsIT extends MoneyApiTestBase {

    @Autowired SubscriptionRepository subscriptions;
    @Autowired BillingOrderRepository orders;
    @Autowired BillingChangeRepository changes;
    @Autowired OrderActivator activator;

    private String customer(String tail) {
        return "/api/billing/customers/" + acme.getId() + tail;
    }

    private Subscription sub() {
        return subscriptions.findByCustomerId(acme.getId()).orElseThrow();
    }

    private static JsonNode byKind(JsonNode list, String kind) {
        for (JsonNode o : list) if (o.get("kind").asString().equals(kind)) return o;
        throw new AssertionError("no " + kind);
    }

    @Test
    void aCustomerDoesNotSeeWhatStaffWroteForThemselves() throws Exception {
        Map<String, Object> discounted = request("NEW", "gold-monthly");
        discounted.put("extraDiscountPercent", 10);
        discounted.put("extraDiscountReason", "Internal: keep this one happy");
        String id = raise(acme, discounted).get("id").asString();
        Map<String, Object> payment = upi("UTR-1");
        payment.put("note", "Internal: paid late");
        POST("/api/billing/orders/" + id + "/pay", amToken, payment).andExpect(status().isOk());
        POST(customer("/adjustments"), superToken, Map.of("creditAmount", 250, "reason", "Internal: complaint")).andExpect(status().isOk());
        Map<String, Object> refund = new LinkedHashMap<>(Map.of("amount", 100, "method", "UPI", "reference", "R-1", "paidOn", "2026-12-01"));
        POST(customer("/refunds"), superToken, refund).andExpect(status().isCreated());

        JsonNode mine = json(GET("/api/my/billing/orders", adminToken).andExpect(status().isOk()));
        JsonNode plan = byKind(mine, "NEW");
        assertThat(plan.get("extraDiscountReason").isNull()).isTrue();
        assertThat(plan.get("extraDiscountPercent").decimalValue()).isEqualByComparingTo("10");
        assertThat(plan.at("/payment/note").isNull()).isTrue();
        assertThat(plan.at("/payment/recordedByName").isNull()).isTrue();
        assertThat(plan.at("/payment/reference").asString()).isEqualTo("UTR-1");
        assertThat(plan.get("raisedByName").asString()).isEqualTo("Your account manager");
        JsonNode adjust = byKind(mine, "ADJUST");
        assertThat(adjust.get("reason").isNull()).isTrue();
        assertThat(adjust.get("creditPosting").decimalValue()).isEqualByComparingTo("250");
        GET("/api/my/billing/refunds", adminToken).andExpect(jsonPath("$[0].recordedByName").doesNotExist());

        // staff still see all of it
        JsonNode staff = json(GET(customer("/orders"), amToken));
        assertThat(byKind(staff, "ADJUST").get("reason").asString()).isEqualTo("Internal: complaint");
        assertThat(byKind(staff, "NEW").at("/payment/note").asString()).isEqualTo("Internal: paid late");
        GET(customer("/refunds"), amToken).andExpect(jsonPath("$[0].recordedByName").isNotEmpty());
    }

    @Test
    void aPlanInGraceCanBeCancelledAndACancelledPlanCannotBeExtended() throws Exception {
        buy(acme, request("NEW", "gold-monthly"));
        on(day(2027, 1, 3));
        GET(customer("/subscription"), amToken).andExpect(jsonPath("$.state").value("GRACE"));
        POST(customer("/cancellations"), superToken, Map.of("reason", "Not paying")).andExpect(status().isOk());
        GET(customer("/subscription"), amToken).andExpect(jsonPath("$.state").value("EXPIRED"))
                .andExpect(jsonPath("$.cancelled").value(true)).andExpect(jsonPath("$.termEnd").value("2026-12-31T18:30:00Z"));
        POST(customer("/cancellations"), superToken, Map.of("reason", "Again")).andExpect(status().isBadRequest());
        POST(customer("/adjustments"), amToken, Map.of("termEnd", "2027-02-01", "reason", "x")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.termEnd").value("The plan was cancelled; start a new one"));
    }

    @Test
    void anAdjustmentShowsTheTermItMoved() throws Exception {
        buy(acme, request("NEW", "gold-monthly"));
        on(day(2026, 12, 20));
        buy(acme, request("RENEWAL", null));
        POST(customer("/adjustments"), amToken, Map.of("termEnd", "2027-02-06", "reason", "Five days")).andExpect(status().isOk())
                .andExpect(jsonPath("$.termStart").value("2026-12-31T18:30:00Z"))
                .andExpect(jsonPath("$.newTermEnd").value("2027-02-05T18:30:00Z"));
    }

    @Test
    void theSameAddOnIsNotRequestedTwice() throws Exception {
        buy(acme, request("NEW", "gold-monthly"));
        on(day(2026, 12, 11));
        POST("/api/my/billing/orders", adminToken, addOnRequest("automations")).andExpect(status().isCreated());
        POST("/api/my/billing/orders", adminToken, addOnRequest("automations")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_PENDING"));
        // extra units are another matter: each request adds to the last
        POST("/api/my/billing/orders", adminToken, usersRequest(5)).andExpect(status().isCreated());
        POST("/api/my/billing/orders", adminToken, usersRequest(5)).andExpect(status().isCreated());
    }

    @Test
    void finishingAnOrderAgainDoesNotOutdateRequestsRaisedAfterIt() throws Exception {
        buy(acme, request("NEW", "gold-monthly"));
        on(day(2026, 12, 11));
        String addOn = buy(acme, usersRequest(5)).get("id").asString();
        String renewal = raise(acme, request("RENEWAL", null)).get("id").asString();
        // the add-on's activation is driven again, as after a stop between its steps
        BillingOrder o = orders.findById(addOn).orElseThrow();
        o.setStatus(OrderStatus.ACTIVATING);
        o.getSteps().clear();
        orders.save(o);
        activator.finishPending(acme.getId());
        assertThat(orders.findById(addOn).orElseThrow().getStatus()).isEqualTo(OrderStatus.ACTIVATED);
        assertThat(orders.findById(renewal).orElseThrow().getStatus()).isEqualTo(OrderStatus.REQUESTED);
        assertThat(sub().getCurrent().getAddOns()).hasSize(1);
    }

    @Test
    void datesAndAmountsFarOutOfRangeAreRefused() throws Exception {
        String id = raise(acme, request("NEW", "gold-monthly")).get("id").asString();
        Map<String, Object> old = upi("UTR-1");
        old.put("receivedOn", "2024-01-01");
        POST("/api/billing/orders/" + id + "/pay", amToken, old).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.receivedOn").exists());
        pay(id).andExpect(status().isOk());
        POST(customer("/adjustments"), superToken, Map.of("creditAmount", 100000001, "reason", "typo")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.creditAmount").exists());
        POST(customer("/adjustments"), superToken, Map.of("creditAmount", 500, "reason", "ok")).andExpect(status().isOk());
        Map<String, Object> refund = new LinkedHashMap<>(Map.of("amount", 100, "method", "UPI", "reference", "R-1", "paidOn", "2024-01-01"));
        POST(customer("/refunds"), superToken, refund).andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.paidOn").exists());
    }

    @Test
    void theSettingsHistoryKeepsWhatAnInvoiceDependsOn() throws Exception {
        Map<String, Object> s = settings();
        s.put("gstRatePercent", 12);
        s.put("version", json(GET("/api/billing/settings", superToken)).get("version").asLong());
        PUT("/api/billing/settings", superToken, s).andExpect(status().isOk());
        List<BillingChange> history = changes.findByTargetTypeAndTargetIdOrderByAtDescIdDesc(BillingChange.SETTINGS, BillingSettings.ID);
        assertThat(history).anySatisfy(c -> {
            assertThat(String.valueOf(c.getBefore().get("gstRatePercent"))).startsWith("18");
            assertThat(String.valueOf(c.getAfter().get("gstRatePercent"))).startsWith("12");
            assertThat(c.getAfter()).containsKeys("supplierLegalName", "supplierStateCode", "supplierGstin", "invoicePrefix");
        });
    }

    @Test
    void staffSeeWhatTheCustomerMayBeSold() throws Exception {
        buy(acme, request("NEW", "gold-monthly"));
        JsonNode o = json(GET(customer("/options"), amToken).andExpect(status().isOk()));
        assertThat(o.get("addOns")).hasSize(2);
        assertThat(o.at("/addOns/0/groupCode").asString()).isEqualTo("automations");
        assertThat(o.at("/addOns/1/limitKey").asString()).isEqualTo("USERS");
        GET(customer("/options"), adminToken).andExpect(status().isForbidden());
    }
}
