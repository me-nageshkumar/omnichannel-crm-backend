package me.nageshk.omnichannel.app.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Map;
import me.nageshk.omnichannel.common.billing.LimitKey;
import me.nageshk.omnichannel.common.billing.OrderStatus;
import me.nageshk.omnichannel.db.billing.BillingOrderRepository;
import me.nageshk.omnichannel.db.billing.Subscription;
import me.nageshk.omnichannel.db.billing.SubscriptionAddOn;
import me.nageshk.omnichannel.db.billing.SubscriptionRepository;
import me.nageshk.omnichannel.security.entitlement.Entitlements;
import me.nageshk.omnichannel.service.billing.order.OrderActivator;
import me.nageshk.omnichannel.service.billing.order.OrderSteps;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;

/** Recording a payment makes the order real. The worked example of the plan, step by step. */
class ActivationIT extends MoneyApiTestBase {

    static final Instant DEC_1_MIDNIGHT = Instant.parse("2026-11-30T18:30:00Z");
    static final Instant JAN_1_MIDNIGHT = Instant.parse("2026-12-31T18:30:00Z");

    @Autowired SubscriptionRepository subscriptions;
    @Autowired BillingOrderRepository orders;
    @Autowired Entitlements entitlements;
    @Autowired OrderActivator activator;
    @MockitoSpyBean OrderSteps steps;

    private Subscription sub() {
        return subscriptions.findByCustomerId(acme.getId()).orElseThrow();
    }

    private String subscriptionPath() {
        return "/api/billing/customers/" + acme.getId() + "/subscription";
    }

    @Test
    void payingForANewPlanPutsTheCustomerOnIt() throws Exception {
        assertThat(entitlements.allows(acme.getId(), "automation:view")).isTrue();
        String id = raise(acme, request("NEW", "gold-monthly")).get("id").asString();

        JsonNode paid = json(pay(id).andExpect(status().isOk()));
        assertThat(paid.get("status").asString()).isEqualTo("ACTIVATED");
        assertThat(paid.at("/payment/method").asString()).isEqualTo("UPI");
        assertThat(paid.at("/payment/reference").asString()).isEqualTo("UTR-" + id);
        assertThat(paid.at("/payment/recordedByName").asString()).isNotBlank();

        GET(subscriptionPath(), amToken).andExpect(jsonPath("$.state").value("ACTIVE"))
                .andExpect(jsonPath("$.plan.code").value("gold-monthly"));
        Subscription s = sub();
        assertThat(s.getCurrent().getTermStart()).isEqualTo(DEC_1_MIDNIGHT);
        assertThat(s.getCurrent().getTermEnd()).isEqualTo(JAN_1_MIDNIGHT);
        assertThat(s.getCurrent().getPlanPaid()).isEqualByComparingTo("900.00");
        assertThat(s.getCurrent().getPlanInvoiceId()).isEqualTo(orders.findById(id).orElseThrow().getInvoiceId());
        assertThat(s.getAnchorDay()).isEqualTo(1);
        assertThat(s.getRevision()).isEqualTo(1);
        // the plan has no automations, and that holds from this moment
        assertThat(entitlements.allows(acme.getId(), "automation:view")).isFalse();
        assertThat(entitlements.allows(acme.getId(), "crm:view")).isTrue();

        // a second payment is refused and changes nothing
        pay(id).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ORDER_NOT_OPEN"));
        assertThat(sub().getRevision()).isEqualTo(1);
        // the history names the order, once
        JsonNode events = json(GET(subscriptionPath() + "/events", amToken));
        long rows = 0;
        for (JsonNode e : events) if (e.get("summary").asString().contains("ORD-000001") && e.get("kind").asString().equals("ORDER_ACTIVATED")) rows++;
        assertThat(rows).isEqualTo(1);
    }

    @Test
    void anAddOnIsAddedForTheRestOfTheTerm() throws Exception {
        buy(acme, request("NEW", "gold-monthly"));
        on(day(2026, 12, 11));
        JsonNode order = raise(acme, addOnRequest("automations"));
        assertThat(order.get("taxable").decimalValue()).isEqualByComparingTo("203.23");
        pay(order.get("id").asString()).andExpect(status().isOk());

        Subscription s = sub();
        assertThat(s.getCurrent().getAddOns()).hasSize(1);
        SubscriptionAddOn a = s.getCurrent().getAddOns().get(0);
        assertThat(a.getGroupCode()).isEqualTo("automations");
        assertThat(a.getPaid()).isEqualByComparingTo("203.23");
        assertThat(a.getCoveredFrom()).isEqualTo(Instant.parse("2026-12-10T18:30:00Z"));
        assertThat(a.getOrderId()).isEqualTo(order.get("id").asString());
        assertThat(a.getInvoiceId()).isNotBlank();
        assertThat(s.getCurrent().getTermEnd()).isEqualTo(JAN_1_MIDNIGHT);
        assertThat(s.getRevision()).isEqualTo(2);
        assertThat(s.getTermRevision()).isEqualTo(1);
        assertThat(entitlements.allows(acme.getId(), "automation:view")).isTrue();
    }

    @Test
    void twoAddOnsRaisedTogetherBothApply() throws Exception {
        buy(acme, request("NEW", "gold-monthly"));
        on(day(2026, 12, 11));
        String b = raise(acme, addOnRequest("automations")).get("id").asString();
        JsonNode c = raise(acme, usersRequest(10));
        assertThat(c.get("taxable").decimalValue()).isEqualByComparingTo("135.48");
        pay(b).andExpect(status().isOk());
        pay(c.get("id").asString()).andExpect(status().isOk());

        assertThat(sub().getCurrent().getAddOns()).hasSize(2);
        GET(subscriptionPath(), amToken)
                .andExpect(jsonPath("$.limits[?(@.key=='USERS')].limit").value(15));
    }

    @Test
    void theSameGroupIsNotAddedTwice() throws Exception {
        buy(acme, request("NEW", "gold-monthly"));
        on(day(2026, 12, 11));
        String first = raise(acme, addOnRequest("automations")).get("id").asString();
        String second = raise(acme, addOnRequest("automations")).get("id").asString();
        pay(first).andExpect(status().isOk());
        pay(second).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ORDER_STALE"));
        assertThat(sub().getCurrent().getAddOns()).hasSize(1);
    }

    @Test
    void aRenewalQuotedBeforeAnAddOnIsSuperseded() throws Exception {
        buy(acme, request("NEW", "gold-monthly"));
        on(day(2026, 12, 11));
        String renewal = raise(acme, request("RENEWAL", null)).get("id").asString();
        String users = raise(acme, usersRequest(5)).get("id").asString();
        buy(acme, addOnRequest("automations"));

        GET("/api/billing/orders/" + renewal, amToken).andExpect(jsonPath("$.status").value("SUPERSEDED"));
        pay(renewal).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ORDER_NOT_OPEN"));
        // another add-on request is still good
        GET("/api/billing/orders/" + users, amToken).andExpect(jsonPath("$.status").value("REQUESTED"));
        pay(users).andExpect(status().isOk());
    }

    @Test
    void aRenewalPaidEarlyWaitsForItsStart() throws Exception {
        buy(acme, request("NEW", "gold-monthly"));
        on(day(2026, 12, 11));
        buy(acme, addOnRequest("automations"));
        on(day(2026, 12, 20));
        JsonNode renewal = raise(acme, request("RENEWAL", null));
        assertThat(renewal.get("total").decimalValue()).isEqualByComparingTo("1416.00");
        pay(renewal.get("id").asString()).andExpect(status().isOk());

        Subscription s = sub();
        assertThat(s.getCurrent().getTermEnd()).isEqualTo(JAN_1_MIDNIGHT);
        assertThat(s.getCurrent().getPlanPaid()).isEqualByComparingTo("900.00");
        assertThat(s.getNext().getTermStart()).isEqualTo(JAN_1_MIDNIGHT);
        assertThat(s.getNext().getTermEnd()).isEqualTo(Instant.parse("2027-01-31T18:30:00Z"));
        assertThat(s.getNext().getAddOns()).hasSize(1);
        assertThat(s.getNext().getAddOns().get(0).getId()).isEqualTo(s.getCurrent().getAddOns().get(0).getId());
        assertThat(s.getAnchorDay()).isEqualTo(1);
        GET(subscriptionPath(), amToken).andExpect(jsonPath("$.state").value("ACTIVE"))
                .andExpect(jsonPath("$.termEnd").value("2027-01-31T18:30:00Z"));
        // an add-on bought now goes onto both terms
        JsonNode users = raise(acme, usersRequest(5));
        assertThat(users.get("lines")).hasSize(2);
        pay(users.get("id").asString()).andExpect(status().isOk());
        assertThat(sub().getCurrent().getAddOns()).hasSize(2);
        assertThat(sub().getNext().getAddOns()).hasSize(2);
    }

    @Test
    void aQuoteCannotBePaidAfterItsValidity() throws Exception {
        String id = raise(acme, request("NEW", "gold-monthly")).get("id").asString();
        on(day(2026, 12, 9));
        pay(id).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ORDER_EXPIRED"));
        assertThat(subscriptions.findByCustomerId(acme.getId())).isEmpty();
    }

    @Test
    void thePaymentMustBeDescribed() throws Exception {
        String id = raise(acme, request("NEW", "gold-monthly")).get("id").asString();
        String path = "/api/billing/orders/" + id + "/pay";
        POST(path, amToken, Map.of("method", "NONE")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.method").exists());
        POST(path, amToken, Map.of("method", "UPI", "receivedOn", "2026-12-01")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.reference").exists());
        POST(path, amToken, Map.of("method", "UPI", "reference", "x")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.receivedOn").exists());
        POST(path, amToken, Map.of("method", "UPI", "reference", "x", "receivedOn", "2026-12-02"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.receivedOn").exists());
        POST(path, amToken, Map.of("method", "BARTER", "reference", "x", "receivedOn", "2026-12-01"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.method").exists());
        POST(path, adminToken, upi("x")).andExpect(status().isForbidden());
        assertThat(orders.findById(id).orElseThrow().getStatus()).isEqualTo(OrderStatus.REQUESTED);
    }

    @Test
    void anOrderWithNothingToPayIsActivatedWithoutAPayment() throws Exception {
        Map<String, Object> free = request("NEW", "gold-monthly");
        free.put("extraDiscountPercent", 100);
        free.put("extraDiscountReason", "Pilot customer");
        JsonNode order = raise(acme, free);
        assertThat(order.get("payable").decimalValue()).isEqualByComparingTo("0");
        String path = "/api/billing/orders/" + order.get("id").asString() + "/pay";
        POST(path, amToken, upi("x")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.method").exists());
        POST(path, amToken, Map.of("method", "NONE")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVATED")).andExpect(jsonPath("$.payment.method").value("NONE"));
        assertThat(sub().getCurrent().getPlanPaid()).isEqualByComparingTo("0");
        // nothing was charged, so nothing can come back as credit
        assertThat(sub().getCurrent().getPlanInvoiceId()).isNull();
    }

    @Test
    void anActivationThatBreaksHalfWayIsFinishedLater() throws Exception {
        String id = raise(acme, request("NEW", "gold-monthly")).get("id").asString();
        doThrow(new IllegalStateException("the database went away")).when(steps).creditNotes(any());
        assertThatThrownBy(() -> pay(id)).hasRootCauseMessage("the database went away");

        assertThat(orders.findById(id).orElseThrow().getStatus()).isEqualTo(OrderStatus.ACTIVATING);
        assertThat(sub().getRevision()).isEqualTo(1);
        GET("/api/billing/orders/" + id, amToken).andExpect(jsonPath("$.status").value("ACTIVATING"));

        doCallRealMethod().when(steps).creditNotes(any());
        activator.finishPending(acme.getId());
        assertThat(orders.findById(id).orElseThrow().getStatus()).isEqualTo(OrderStatus.ACTIVATED);
        assertThat(sub().getRevision()).isEqualTo(1);
        assertThat(sub().getCurrent().getPlanPaid()).isEqualByComparingTo("900.00");
        // and the next change for the customer is not held up
        on(day(2026, 12, 11));
        buy(acme, usersRequest(5));
        assertThat(sub().getCurrent().getAddOns()).hasSize(1);
        assertThat(sub().getCurrent().getAddOns().get(0).getLimitKey()).isEqualTo(LimitKey.USERS);
    }

    @Test
    void theNextPaymentFinishesAnInterruptedOneFirst() throws Exception {
        buy(acme, request("NEW", "gold-monthly"));
        on(day(2026, 12, 11));
        String broken = raise(acme, usersRequest(5)).get("id").asString();
        String next = raise(acme, addOnRequest("automations")).get("id").asString();
        doThrow(new IllegalStateException("boom")).when(steps).invoice(any());
        assertThatThrownBy(() -> pay(broken)).hasRootCauseMessage("boom");
        doCallRealMethod().when(steps).invoice(any());

        pay(next).andExpect(status().isOk());
        assertThat(orders.findById(broken).orElseThrow().getStatus()).isEqualTo(OrderStatus.ACTIVATED);
        assertThat(sub().getCurrent().getAddOns()).hasSize(2);
        assertThat(sub().getRevision()).isEqualTo(3);
    }
}
