package me.nageshk.omnichannel.app.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Set;
import me.nageshk.omnichannel.common.billing.LimitKey;
import me.nageshk.omnichannel.db.billing.BillingOrder;
import me.nageshk.omnichannel.db.billing.BillingOrderRepository;
import me.nageshk.omnichannel.db.billing.InvoiceRepository;
import me.nageshk.omnichannel.db.billing.Subscription;
import me.nageshk.omnichannel.db.billing.SubscriptionRepository;
import me.nageshk.omnichannel.service.billing.document.InvoiceService;
import me.nageshk.omnichannel.service.billing.order.OrderSteps;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/** Every paid order issues one tax invoice: a snapshot that later changes do not touch. */
class InvoicesIT extends MoneyApiTestBase {

    @Autowired InvoiceRepository invoices;
    @Autowired BillingOrderRepository orders;
    @Autowired SubscriptionRepository subscriptions;
    @Autowired OrderSteps steps;
    @Autowired InvoiceService invoiceService;

    private String invoicesPath(me.nageshk.omnichannel.db.tenant.Customer c) {
        return "/api/billing/customers/" + c.getId() + "/invoices";
    }

    @Test
    void aPaidOrderIssuesATaxInvoice() throws Exception {
        JsonNode order = buy(acme, request("NEW", "gold-monthly"));
        assertThat(order.get("invoiceNumber").asString()).isEqualTo("INV/26-27/000001");

        JsonNode inv = json(GET("/api/billing/invoices/" + order.get("invoiceId").asString(), amToken)
                .andExpect(status().isOk()));
        assertThat(inv.get("number").asString()).isEqualTo("INV/26-27/000001");
        assertThat(inv.get("orderNumber").asString()).isEqualTo("ORD-000001");
        assertThat(inv.get("issuedAt").asString()).isEqualTo("2026-12-01T05:00:00Z");
        assertThat(inv.at("/supplier/legalName").asString()).isEqualTo("Omni Cloud Pvt Ltd");
        assertThat(inv.at("/supplier/stateName").asString()).isEqualTo("Karnataka");
        assertThat(inv.at("/supplier/gstin").asString()).isEqualTo("29AAACO1234A1Z5");
        assertThat(inv.at("/customer/legalName").asString()).isEqualTo("Acme Retail Pvt Ltd");
        assertThat(inv.at("/customer/stateCode").asString()).isEqualTo("29");
        assertThat(inv.at("/customer/stateName").asString()).isEqualTo("Karnataka");
        assertThat(inv.get("sacCode").asString()).isEqualTo("998314");
        assertThat(inv.get("lines")).hasSize(1);
        assertThat(inv.at("/lines/0/description").asString()).isEqualTo("Gold, monthly (1 Dec 2026 – 31 Dec 2026)");
        assertThat(inv.at("/lines/0/amount").decimalValue()).isEqualByComparingTo("900.00");
        assertThat(inv.get("taxable").decimalValue()).isEqualByComparingTo("900.00");
        assertThat(inv.at("/tax/cgst").decimalValue()).isEqualByComparingTo("81.00");
        assertThat(inv.at("/tax/sgst").decimalValue()).isEqualByComparingTo("81.00");
        assertThat(inv.at("/tax/igst").decimalValue()).isEqualByComparingTo("0");
        assertThat(inv.get("total").decimalValue()).isEqualByComparingTo("1062.00");
        assertThat(inv.get("totalInWords").asString()).isEqualTo("Rupees One Thousand Sixty-Two Only");
        assertThat(inv.get("accountCreditUsed").decimalValue()).isEqualByComparingTo("0");
        assertThat(inv.get("amountPaid").decimalValue()).isEqualByComparingTo("1062.00");
        assertThat(inv.at("/payment/reference").asString()).isEqualTo("UTR-" + order.get("id").asString());
        assertThat(inv.get("credited").decimalValue()).isEqualByComparingTo("0");
        assertThat(inv.get("hasPdf").asBoolean()).isTrue();

        on(day(2026, 12, 11));
        JsonNode addOn = buy(acme, addOnRequest("automations"));
        assertThat(addOn.get("invoiceNumber").asString()).isEqualTo("INV/26-27/000002");
        JsonNode list = json(GET(invoicesPath(acme), amToken).andExpect(status().isOk()));
        assertThat(list).hasSize(2);
        assertThat(list.at("/0/number").asString()).isEqualTo("INV/26-27/000002");
        assertThat(list.at("/0/total").decimalValue()).isEqualByComparingTo("239.81");
    }

    @Test
    void aCustomerInAnotherStateIsChargedIgst() throws Exception {
        JsonNode order = buy(bolt, request("NEW", "gold-monthly"));
        JsonNode inv = json(GET("/api/billing/invoices/" + order.get("invoiceId").asString(), amToken));
        assertThat(inv.at("/tax/interState").asBoolean()).isTrue();
        assertThat(inv.at("/tax/igst").decimalValue()).isEqualByComparingTo("162.00");
        assertThat(inv.at("/tax/cgst").decimalValue()).isEqualByComparingTo("0");
        assertThat(inv.at("/customer/stateName").asString()).isEqualTo("Maharashtra");
        assertThat(inv.get("total").decimalValue()).isEqualByComparingTo("1062.00");
    }

    @Test
    void aFreeOrderIssuesNoInvoice() throws Exception {
        Map<String, Object> free = request("NEW", "gold-monthly");
        free.put("extraDiscountPercent", 100);
        free.put("extraDiscountReason", "Pilot customer");
        String id = raise(acme, free).get("id").asString();
        JsonNode order = json(POST("/api/billing/orders/" + id + "/pay", amToken, Map.of("method", "NONE"))
                .andExpect(status().isOk()));
        assertThat(order.get("invoiceNumber").isNull()).isTrue();
        assertThat(order.get("invoiceId").isNull()).isTrue();
        assertThat(invoices.count()).isZero();
    }

    @Test
    void anInvoiceKeepsWhatWasTrueWhenItWasIssued() throws Exception {
        JsonNode order = buy(acme, request("NEW", "gold-monthly"));
        PUT(profilePath(acme), amToken, profile("Acme Global Ltd", "5 New Road, Mumbai 400001", "27")).andExpect(status().isOk());
        Map<String, Object> s = settings();
        s.put("supplierLegalName", "Omni Cloud Limited");
        s.put("version", json(GET("/api/billing/settings", superToken)).get("version").asLong());
        PUT("/api/billing/settings", superToken, s).andExpect(status().isOk());

        GET("/api/billing/invoices/" + order.get("invoiceId").asString(), amToken)
                .andExpect(jsonPath("$.customer.legalName").value("Acme Retail Pvt Ltd"))
                .andExpect(jsonPath("$.customer.stateCode").value("29"))
                .andExpect(jsonPath("$.supplier.legalName").value("Omni Cloud Pvt Ltd"));
    }

    @Test
    void accountCreditIsSetAgainstTheTotalOnce() throws Exception {
        // a lapsed customer that is owed 500.00
        Subscription lapsed = billing.assign(acme.getId(), Set.of("crm"), Map.of(LimitKey.USERS, 5),
                clock.instant().minus(60, ChronoUnit.DAYS), clock.instant().minus(30, ChronoUnit.DAYS));
        lapsed.setCreditBalance(new BigDecimal("500.00"));
        subscriptions.save(lapsed);

        JsonNode order = raise(acme, request("NEW", "gold-monthly"));
        assertThat(order.get("accountCreditUsed").decimalValue()).isEqualByComparingTo("500.00");
        assertThat(order.get("payable").decimalValue()).isEqualByComparingTo("562.00");
        String id = order.get("id").asString();
        JsonNode paid = json(pay(id).andExpect(status().isOk()));

        JsonNode inv = json(GET("/api/billing/invoices/" + paid.get("invoiceId").asString(), amToken));
        assertThat(inv.get("total").decimalValue()).isEqualByComparingTo("1062.00");
        assertThat(inv.get("accountCreditUsed").decimalValue()).isEqualByComparingTo("500.00");
        assertThat(inv.get("amountPaid").decimalValue()).isEqualByComparingTo("562.00");
        assertThat(subscriptions.findByCustomerId(acme.getId()).orElseThrow().getCreditBalance()).isEqualByComparingTo("0");

        // the step run again (an activation finished after a failure) takes nothing more and issues nothing more
        BillingOrder stored = orders.findById(id).orElseThrow();
        steps.invoice(stored);
        steps.invoice(stored);
        assertThat(subscriptions.findByCustomerId(acme.getId()).orElseThrow().getCreditBalance()).isEqualByComparingTo("0");
        assertThat(invoices.count()).isEqualTo(1);
    }

    @Test
    void anInvoiceInsertedBeforeAFailureIsNotIssuedAgain() throws Exception {
        String id = raise(acme, request("NEW", "gold-monthly")).get("id").asString();
        // as if the server had stopped right after inserting the invoice
        String number = invoiceService.issue(orders.findById(id).orElseThrow()).getNumber();
        JsonNode paid = json(pay(id).andExpect(status().isOk()));
        assertThat(paid.get("invoiceNumber").asString()).isEqualTo(number).isEqualTo("INV/26-27/000001");
        assertThat(invoices.count()).isEqualTo(1);
    }

    @Test
    void creditSpentElsewhereMakesTheOrderStale() throws Exception {
        Subscription lapsed = billing.assign(acme.getId(), Set.of("crm"), Map.of(LimitKey.USERS, 5),
                clock.instant().minus(60, ChronoUnit.DAYS), clock.instant().minus(30, ChronoUnit.DAYS));
        lapsed.setCreditBalance(new BigDecimal("500.00"));
        subscriptions.save(lapsed);
        String id = raise(acme, request("NEW", "gold-monthly")).get("id").asString();
        // the credit is gone (refunded, say) before the payment is recorded
        Subscription now = subscriptions.findByCustomerId(acme.getId()).orElseThrow();
        now.setCreditBalance(new BigDecimal("100.00"));
        subscriptions.save(now);

        pay(id).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ORDER_STALE"));
        assertThat(subscriptions.findByCustomerId(acme.getId()).orElseThrow().getCreditBalance()).isEqualByComparingTo("100.00");
    }

    @Test
    void aCustomerSeesItsOwnInvoicesOnly() throws Exception {
        String mine = buy(acme, request("NEW", "gold-monthly")).get("invoiceId").asString();
        String theirs = buy(bolt, request("NEW", "gold-monthly")).get("invoiceId").asString();

        GET("/api/my/billing/invoices", adminToken).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].number").value("INV/26-27/000001"));
        GET("/api/my/billing/invoices/" + mine, adminToken).andExpect(status().isOk());
        GET("/api/my/billing/invoices/" + theirs, adminToken).andExpect(status().isNotFound());
        // the staff paths are closed to it
        GET("/api/billing/invoices/" + mine, adminToken).andExpect(status().isForbidden());
        GET(invoicesPath(acme), adminToken).andExpect(status().isForbidden());
        GET("/api/billing/invoices/000000000000000000000000", amToken).andExpect(status().isNotFound());
    }
}
