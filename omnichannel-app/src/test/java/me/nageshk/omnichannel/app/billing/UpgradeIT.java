package me.nageshk.omnichannel.app.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.nageshk.omnichannel.common.billing.LimitKey;
import me.nageshk.omnichannel.common.billing.OrderStatus;
import me.nageshk.omnichannel.db.billing.BillingOrderRepository;
import me.nageshk.omnichannel.db.billing.CreditNoteRepository;
import me.nageshk.omnichannel.db.billing.InvoiceRepository;
import me.nageshk.omnichannel.db.billing.PlanRepository;
import me.nageshk.omnichannel.db.billing.PlanTerms;
import me.nageshk.omnichannel.db.billing.Subscription;
import me.nageshk.omnichannel.db.billing.SubscriptionRepository;
import me.nageshk.omnichannel.security.entitlement.Entitlements;
import me.nageshk.omnichannel.service.billing.document.CreditNoteService;
import me.nageshk.omnichannel.service.billing.order.OrderActivator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;

/** Moving to another plan: the unused part of what was paid comes back as credit notes and pays for the new term. */
class UpgradeIT extends MoneyApiTestBase {

    @Autowired SubscriptionRepository subscriptions;
    @Autowired BillingOrderRepository orders;
    @Autowired InvoiceRepository invoices;
    @Autowired CreditNoteRepository creditNotes;
    @Autowired PlanRepository plans;
    @Autowired Entitlements entitlements;
    @Autowired OrderActivator activator;
    @MockitoSpyBean CreditNoteService creditNoteService;

    private Subscription sub() {
        return subscriptions.findByCustomerId(acme.getId()).orElseThrow();
    }

    /** Steps A and B of the worked example; returns the ids of invoices A and B. */
    private String[] goldWithAutomations() throws Exception {
        String a = buy(acme, request("NEW", "gold-monthly")).get("invoiceId").asString();
        on(day(2026, 12, 11));
        String b = buy(acme, addOnRequest("automations")).get("invoiceId").asString();
        return new String[] {a, b};
    }

    private void plan(String code, String name, int rank, int price) throws Exception {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("code", code);
        p.put("name", name);
        p.put("period", "MONTHLY");
        p.put("rank", rank);
        p.put("items", List.of(Map.of("groupCode", "crm", "price", price)));
        p.put("discountPercent", 0);
        p.put("offered", true);
        POST("/api/billing/plans", superToken, p).andExpect(status().isCreated());
        POST("/api/billing/plans/" + code + "/activate", superToken, null).andExpect(status().isOk());
    }

    @Test
    void anUpgradeCreditsTheUnusedDaysAndChargesTheRest() throws Exception {
        String[] inv = goldWithAutomations();
        on(day(2026, 12, 16));
        JsonNode order = raise(acme, request("UPGRADE", "platinum-monthly"));
        assertThat(order.get("credits")).hasSize(2);
        assertThat(order.at("/credits/0/invoiceNumber").asString()).isEqualTo("INV/2026-27/000001");
        assertThat(order.at("/credits/0/taxable").decimalValue()).isEqualByComparingTo("464.52");
        assertThat(order.at("/credits/0/total").decimalValue()).isEqualByComparingTo("548.13");
        assertThat(order.at("/credits/1/taxable").decimalValue()).isEqualByComparingTo("154.84");
        assertThat(order.at("/credits/1/total").decimalValue()).isEqualByComparingTo("182.71");
        assertThat(order.get("total").decimalValue()).isEqualByComparingTo("1770.00");
        assertThat(order.get("payable").decimalValue()).isEqualByComparingTo("1039.16");
        assertThat(order.get("creditNoteIds")).isEmpty();

        JsonNode paid = json(pay(order.get("id").asString()).andExpect(status().isOk()));
        assertThat(paid.get("status").asString()).isEqualTo("ACTIVATED");
        assertThat(paid.get("creditNoteIds")).hasSize(2);
        assertThat(paid.get("invoiceNumber").asString()).isEqualTo("INV/2026-27/000003");

        JsonNode notes = json(GET("/api/billing/customers/" + acme.getId() + "/credit-notes", amToken).andExpect(status().isOk()));
        assertThat(notes).hasSize(2);
        JsonNode first = json(GET("/api/billing/credit-notes/" + paid.at("/creditNoteIds/0").asString(), amToken));
        assertThat(first.get("number").asString()).isEqualTo("CN/2026-27/000001");
        assertThat(first.get("invoiceId").asString()).isEqualTo(inv[0]);
        assertThat(first.get("invoiceNumber").asString()).isEqualTo("INV/2026-27/000001");
        assertThat(first.get("invoiceDate").asString()).isEqualTo("2026-12-01T05:00:00Z");
        assertThat(first.get("taxable").decimalValue()).isEqualByComparingTo("464.52");
        assertThat(first.at("/tax/cgst").decimalValue()).isEqualByComparingTo("41.81");
        assertThat(first.at("/tax/sgst").decimalValue()).isEqualByComparingTo("41.80");
        assertThat(first.get("total").decimalValue()).isEqualByComparingTo("548.13");
        assertThat(first.get("reason").asString()).isEqualTo("Unused time on upgrade");
        assertThat(first.at("/customer/legalName").asString()).isEqualTo("Acme Retail Pvt Ltd");
        assertThat(first.get("totalInWords").asString()).startsWith("Rupees Five Hundred Forty-Eight");
        JsonNode second = json(GET("/api/billing/credit-notes/" + paid.at("/creditNoteIds/1").asString(), amToken));
        assertThat(second.get("number").asString()).isEqualTo("CN/2026-27/000002");
        assertThat(second.get("invoiceId").asString()).isEqualTo(inv[1]);
        assertThat(second.get("taxable").decimalValue()).isEqualByComparingTo("154.84");
        assertThat(second.get("total").decimalValue()).isEqualByComparingTo("182.71");

        JsonNode third = json(GET("/api/billing/invoices/" + paid.get("invoiceId").asString(), amToken));
        assertThat(third.get("total").decimalValue()).isEqualByComparingTo("1770.00");
        assertThat(third.get("accountCreditUsed").decimalValue()).isEqualByComparingTo("730.84");
        assertThat(third.get("amountPaid").decimalValue()).isEqualByComparingTo("1039.16");
        GET("/api/billing/invoices/" + inv[0], amToken).andExpect(jsonPath("$.credited").value(464.52));
        GET("/api/billing/invoices/" + inv[1], amToken).andExpect(jsonPath("$.credited").value(154.84));

        Subscription s = sub();
        assertThat(s.getCurrent().getPlanCode()).isEqualTo("platinum-monthly");
        assertThat(s.getCurrent().getTermStart()).isEqualTo(Instant.parse("2026-12-15T18:30:00Z"));
        assertThat(s.getCurrent().getTermEnd()).isEqualTo(Instant.parse("2027-01-15T18:30:00Z"));
        assertThat(s.getCurrent().getAddOns()).isEmpty();
        assertThat(s.getAnchorDay()).isEqualTo(16);
        assertThat(s.getCreditBalance()).isEqualByComparingTo("0");
        assertThat(entitlements.allows(acme.getId(), "automation:view")).isTrue();
    }

    @Test
    void aSecondUpgradeCreditsTheNewestInvoiceOnly() throws Exception {
        goldWithAutomations();
        on(day(2026, 12, 16));
        buy(acme, request("UPGRADE", "platinum-monthly"));
        plan("diamond-monthly", "Diamond", 4, 3000);
        on(day(2026, 12, 17));
        JsonNode order = raise(acme, request("UPGRADE", "diamond-monthly"));
        assertThat(order.get("credits")).hasSize(1);
        assertThat(order.at("/credits/0/invoiceNumber").asString()).isEqualTo("INV/2026-27/000003");
        // 1500 for 31 days, 30 unused
        assertThat(order.at("/credits/0/taxable").decimalValue()).isEqualByComparingTo("1451.61");
    }

    @Test
    void creditAboveTheNewPriceStaysOnTheAccount() throws Exception {
        buy(acme, request("NEW", "gold-monthly"));
        plan("bronze-monthly", "Bronze", 1, 100);
        on(day(2026, 12, 2));
        Map<String, Object> down = request("CHANGE", "bronze-monthly");
        down.put("addOns", List.of());
        JsonNode order = raise(acme, down);
        // 900 for 31 days, 30 unused: 870.97 + 156.77 tax
        assertThat(order.get("creditFromNotes").decimalValue()).isEqualByComparingTo("1027.74");
        assertThat(order.get("total").decimalValue()).isEqualByComparingTo("118.00");
        assertThat(order.get("accountCreditUsed").decimalValue()).isEqualByComparingTo("118.00");
        assertThat(order.get("payable").decimalValue()).isEqualByComparingTo("0");

        JsonNode paid = json(POST("/api/billing/orders/" + order.get("id").asString() + "/pay", amToken, Map.of("method", "NONE"))
                .andExpect(status().isOk()));
        assertThat(paid.get("creditNoteIds")).hasSize(1);
        JsonNode note = json(GET("/api/billing/credit-notes/" + paid.at("/creditNoteIds/0").asString(), amToken));
        assertThat(note.get("reason").asString()).isEqualTo("Unused time on plan change");
        assertThat(sub().getCreditBalance()).isEqualByComparingTo("909.74");
        assertThat(sub().getCurrent().getPlanCode()).isEqualTo("bronze-monthly");
        JsonNode inv = json(GET("/api/billing/invoices/" + paid.get("invoiceId").asString(), amToken));
        assertThat(inv.get("accountCreditUsed").decimalValue()).isEqualByComparingTo("118.00");
        assertThat(inv.get("amountPaid").decimalValue()).isEqualByComparingTo("0");
    }

    @Test
    void aTermThatWasNotPaidForGivesNoCredit() throws Exception {
        // a plan given by hand before orders existed
        Subscription s = billing.assign(acme.getId(), Set.of("crm"), Map.of(LimitKey.USERS, 5));
        s.getCurrent().setPlanCode("gold-monthly");
        s.getCurrent().setTerms(PlanTerms.of(plans.findById("gold-monthly").orElseThrow()));
        subscriptions.save(s);
        entitlements.evict(acme.getId());

        JsonNode order = raise(acme, request("UPGRADE", "platinum-monthly"));
        assertThat(order.get("credits")).isEmpty();
        assertThat(order.get("payable").decimalValue()).isEqualByComparingTo("1770.00");
        pay(order.get("id").asString()).andExpect(status().isOk());
        assertThat(creditNotes.count()).isZero();
        assertThat(sub().getCreditBalance()).isEqualByComparingTo("0");
    }

    @Test
    void anUpgradeThatBreaksBetweenItsCreditNotesIsFinishedWithoutDoubling() throws Exception {
        String[] inv = goldWithAutomations();
        on(day(2026, 12, 16));
        String id = raise(acme, request("UPGRADE", "platinum-monthly")).get("id").asString();
        doCallRealMethod().doThrow(new IllegalStateException("boom")).when(creditNoteService).issue(any(), any());
        assertThatThrownBy(() -> pay(id)).hasRootCauseMessage("boom");
        assertThat(orders.findById(id).orElseThrow().getStatus()).isEqualTo(OrderStatus.ACTIVATING);
        assertThat(creditNotes.count()).isEqualTo(1);

        doCallRealMethod().when(creditNoteService).issue(any(), any());
        activator.finishPending(acme.getId());
        assertThat(orders.findById(id).orElseThrow().getStatus()).isEqualTo(OrderStatus.ACTIVATED);
        assertThat(creditNotes.count()).isEqualTo(2);
        assertThat(invoices.count()).isEqualTo(3);
        assertThat(invoices.findById(inv[0]).orElseThrow().getCredited()).isEqualByComparingTo("464.52");
        assertThat(invoices.findById(inv[1]).orElseThrow().getCredited()).isEqualByComparingTo("154.84");
        // raised by 730.84 once and drawn down by 730.84 once
        assertThat(sub().getCreditBalance()).isEqualByComparingTo("0");
        assertThat(sub().getRevision()).isEqualTo(3);

        // running it all again changes nothing
        activator.finishPending(acme.getId());
        assertThat(creditNotes.count()).isEqualTo(2);
        assertThat(invoices.findById(inv[0]).orElseThrow().getCredited()).isEqualByComparingTo("464.52");
    }
}
