package me.nageshk.omnichannel.app.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import me.nageshk.omnichannel.common.billing.OrderKind;
import me.nageshk.omnichannel.common.billing.OrderStatus;
import me.nageshk.omnichannel.db.billing.BillingOrder;
import me.nageshk.omnichannel.db.billing.BillingOrderRepository;
import me.nageshk.omnichannel.db.billing.Invoice;
import me.nageshk.omnichannel.db.billing.InvoiceRepository;
import me.nageshk.omnichannel.db.billing.OrderPayment;
import me.nageshk.omnichannel.db.billing.Subscription;
import me.nageshk.omnichannel.db.billing.SubscriptionRepository;
import me.nageshk.omnichannel.service.billing.order.OrderActivator;
import me.nageshk.omnichannel.service.billing.order.OrderSteps;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;

/** What the whole-branch review found. */
class ReviewFixesIT extends MoneyApiTestBase {

    @Autowired SubscriptionRepository subscriptions;
    @Autowired BillingOrderRepository orders;
    @Autowired InvoiceRepository invoices;
    @Autowired OrderActivator activator;
    @MockitoSpyBean OrderSteps steps;

    private Subscription sub() {
        return subscriptions.findByCustomerId(acme.getId()).orElseThrow();
    }

    private String adjustments() {
        return "/api/billing/customers/" + acme.getId() + "/adjustments";
    }

    private void saveSettings(String field, Object value) throws Exception {
        Map<String, Object> s = settings();
        s.put(field, value);
        s.put("version", json(GET("/api/billing/settings", superToken)).get("version").asLong());
        PUT("/api/billing/settings", superToken, s).andExpect(status().isOk());
    }

    @Test
    void aRenewalAfterAnExtendedTermIsAFullPeriod() throws Exception {
        buy(acme, request("NEW", "gold-monthly"));
        POST(adjustments(), amToken, Map.of("termEnd", "2027-01-11", "reason", "Ten days for the outage")).andExpect(status().isOk());
        assertThat(sub().getAnchorDay()).isEqualTo(11);
        on(day(2026, 12, 20));
        JsonNode quote = json(POST(quotesPath(acme), amToken, request("RENEWAL", null)).andExpect(status().isOk()));
        assertThat(quote.get("termStart").asString()).isEqualTo("2027-01-10T18:30:00Z");
        assertThat(quote.get("termEnd").asString()).isEqualTo("2027-02-10T18:30:00Z");
    }

    @Test
    void aPlanCannotStartInTheFuture() throws Exception {
        Map<String, Object> later = request("NEW", "gold-monthly");
        later.put("startDate", "2026-12-02");
        POST(quotesPath(acme), amToken, later).andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.startDate").exists());
        later.put("startDate", "2026-11-25");
        POST(quotesPath(acme), amToken, later).andExpect(status().isOk());
    }

    @Test
    void documentNumbersFitTheSixteenCharactersGstAllows() throws Exception {
        JsonNode order = buy(acme, request("NEW", "gold-monthly"));
        assertThat(order.get("invoiceNumber").asString()).isEqualTo("INV/26-27/000001").hasSizeLessThanOrEqualTo(16);
        Map<String, Object> s = settings();
        s.put("invoicePrefix", "INVOICE");
        s.put("version", json(GET("/api/billing/settings", superToken)).get("version").asLong());
        PUT("/api/billing/settings", superToken, s).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.invoicePrefix").exists());
    }

    @Test
    void anInvoiceRemembersTheTaxItsCreditNotesGaveBack() throws Exception {
        String invoiceId = buy(acme, request("NEW", "gold-monthly")).get("invoiceId").asString();
        on(day(2026, 12, 16));
        buy(acme, request("UPGRADE", "platinum-monthly"));
        Invoice inv = invoices.findById(invoiceId).orElseThrow();
        assertThat(inv.getCredited()).isEqualByComparingTo("464.52");
        assertThat(inv.getCreditedCgst()).isEqualByComparingTo("41.81");
        assertThat(inv.getCreditedSgst()).isEqualByComparingTo("41.80");
        assertThat(inv.getCreditedIgst()).isEqualByComparingTo("0");
    }

    @Test
    void aCreditPostingThatFailedAndIsTriedAgainIsPostedOnce() throws Exception {
        buy(acme, request("NEW", "gold-monthly"));
        doThrow(new IllegalStateException("the database went away")).doCallRealMethod().when(steps).apply(any());
        assertThatThrownBy(() -> POST(adjustments(), superToken, Map.of("creditAmount", 250, "reason", "Goodwill")))
                .hasRootCauseMessage("the database went away");
        POST(adjustments(), superToken, Map.of("creditAmount", 250, "reason", "Goodwill")).andExpect(status().isOk());

        assertThat(sub().getCreditBalance()).isEqualByComparingTo("250.00");
        List<BillingOrder> postings = orders.findAll().stream().filter(o -> o.getKind() == OrderKind.ADJUST).toList();
        assertThat(postings).hasSize(2);
        assertThat(postings.stream().map(BillingOrder::getStatus))
                .containsExactlyInAnyOrder(OrderStatus.CANCELLED, OrderStatus.ACTIVATED);
    }

    @Test
    void anOrderIsNotPaidOnTaxSettingsItWasNotPricedOn() throws Exception {
        String id = raise(acme, request("NEW", "gold-monthly")).get("id").asString();
        saveSettings("gstRatePercent", 12);
        pay(id).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ORDER_STALE"));

        saveSettings("gstRatePercent", 18);
        saveSettings("supplierLegalName", "");
        pay(id).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("BILLING_NOT_SET_UP"));
        assertThat(orders.findById(id).orElseThrow().getStatus()).isEqualTo(OrderStatus.REQUESTED);

        saveSettings("supplierLegalName", "Omni Cloud Pvt Ltd");
        // the supplier moved to another state: the order's CGST and SGST would be wrong
        Map<String, Object> moved = settings();
        moved.put("supplierStateCode", "27");
        moved.put("supplierGstin", "27AAACO1234A1Z5");
        moved.put("version", json(GET("/api/billing/settings", superToken)).get("version").asLong());
        PUT("/api/billing/settings", superToken, moved).andExpect(status().isOk());
        pay(id).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ORDER_STALE"));
    }

    @Test
    void anAddOnIsNotKeptOrLeftWithoutTheOneItNeeds() throws Exception {
        Map<String, Object> pro = new LinkedHashMap<>();
        pro.put("code", "pro-monthly");
        pro.put("name", "Pro");
        pro.put("period", "MONTHLY");
        pro.put("rank", 2);
        pro.put("items", List.of(Map.of("groupCode", "crm", "price", 1000), Map.of("groupCode", "automations", "price", 300)));
        pro.put("addOns", List.of(Map.of("groupCode", "chatbots", "price", 200), Map.of("groupCode", "ai", "price", 300)));
        pro.put("discountPercent", 0);
        pro.put("offered", true);
        POST("/api/billing/plans", superToken, pro).andExpect(status().isCreated());
        POST("/api/billing/plans/pro-monthly/activate", superToken, null).andExpect(status().isOk());
        Map<String, Object> withBoth = request("NEW", "pro-monthly");
        withBoth.put("addOns", List.of(Map.of("kind", "GROUP", "groupCode", "chatbots"), Map.of("kind", "GROUP", "groupCode", "ai")));
        buy(acme, withBoth);
        String chatbots = sub().getCurrent().getAddOns().stream().filter(a -> "chatbots".equals(a.getGroupCode())).findFirst().orElseThrow().getId();
        String ai = sub().getCurrent().getAddOns().stream().filter(a -> "ai".equals(a.getGroupCode())).findFirst().orElseThrow().getId();

        on(day(2026, 12, 20));
        Map<String, Object> keepAi = request("RENEWAL", null);
        keepAi.put("keepAddOnIds", List.of(ai));
        POST("/api/my/billing/quotes", adminToken, keepAi).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.keepAddOnIds").value("AI needs Chatbots"));
        keepAi.put("keepAddOnIds", List.of(ai, chatbots));
        POST("/api/my/billing/quotes", adminToken, keepAi).andExpect(status().isOk());

        String cancellations = "/api/billing/customers/" + acme.getId() + "/cancellations";
        POST(cancellations + "/quote", superToken, Map.of("addOnId", chatbots)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.addOnId").value("AI needs Chatbots; cancel it first"));
        POST(cancellations, superToken, Map.of("addOnId", ai, "reason", "Not used")).andExpect(status().isOk());
        POST(cancellations, superToken, Map.of("addOnId", chatbots, "reason", "Not used")).andExpect(status().isOk());
    }

    @Test
    void anAddOnInterruptedAcrossARenewalsStartIsStillFinished() throws Exception {
        buy(acme, request("NEW", "gold-monthly"));
        on(day(2026, 12, 20));
        buy(acme, request("RENEWAL", null));
        on(day(2026, 12, 25));
        String id = raise(acme, usersRequest(5)).get("id").asString();
        // its payment was recorded, the server stopped, and meanwhile the renewal took the old term's place
        BillingOrder o = orders.findById(id).orElseThrow();
        o.setStatus(OrderStatus.ACTIVATING);
        o.setPayment(new OrderPayment("UPI", "UTR-9", LocalDate.of(2026, 12, 25), null, amUser.getId(), clock.instant()));
        o.setActivatingSince(clock.instant());
        orders.save(o);
        on(day(2027, 1, 2));
        Subscription s = sub();
        s.setCurrent(s.getNext());
        s.setNext(null);
        subscriptions.save(s);

        activator.finishPending(acme.getId());
        assertThat(orders.findById(id).orElseThrow().getStatus()).isEqualTo(OrderStatus.ACTIVATED);
        assertThat(sub().getCurrent().getAddOns()).hasSize(1);
    }
}
