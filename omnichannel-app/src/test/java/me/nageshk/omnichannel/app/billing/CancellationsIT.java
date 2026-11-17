package me.nageshk.omnichannel.app.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import me.nageshk.omnichannel.db.billing.CreditNoteRepository;
import me.nageshk.omnichannel.db.billing.Subscription;
import me.nageshk.omnichannel.db.billing.SubscriptionRepository;
import me.nageshk.omnichannel.security.entitlement.Entitlements;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/** Cancelling an add-on or the plan, with the unused part back as credit. Acme: Gold from 1 Dec, automations from 11 Dec; today is 16 Dec. */
class CancellationsIT extends MoneyApiTestBase {

    @Autowired SubscriptionRepository subscriptions;
    @Autowired CreditNoteRepository creditNotes;
    @Autowired Entitlements entitlements;

    String addOnId;

    @BeforeEach
    void goldWithAutomations() throws Exception {
        buy(acme, request("NEW", "gold-monthly"));
        on(day(2026, 12, 11));
        buy(acme, addOnRequest("automations"));
        on(day(2026, 12, 16));
        addOnId = sub().getCurrent().getAddOns().get(0).getId();
    }

    private Subscription sub() {
        return subscriptions.findByCustomerId(acme.getId()).orElseThrow();
    }

    private String path() {
        return "/api/billing/customers/" + acme.getId() + "/cancellations";
    }

    private static Map<String, Object> body(String addOnId, Object creditTaxable, String reason) {
        Map<String, Object> b = new LinkedHashMap<>();
        if (addOnId != null) b.put("addOnId", addOnId);
        if (creditTaxable != null) b.put("creditTaxable", creditTaxable);
        if (reason != null) b.put("reason", reason);
        return b;
    }

    @Test
    void cancellingAnAddOnCreditsItsUnusedDays() throws Exception {
        JsonNode quote = json(POST(path() + "/quote", superToken, body(addOnId, null, null)).andExpect(status().isOk()));
        assertThat(quote.get("kind").asString()).isEqualTo("CANCEL_ADDON");
        assertThat(quote.get("credits")).hasSize(1);
        assertThat(quote.at("/credits/0/taxable").decimalValue()).isEqualByComparingTo("154.84");
        assertThat(quote.at("/credits/0/tax/cgst").decimalValue()).isEqualByComparingTo("13.94");
        assertThat(quote.at("/credits/0/total").decimalValue()).isEqualByComparingTo("182.71");
        assertThat(quote.get("creditFromNotes").decimalValue()).isEqualByComparingTo("182.71");
        assertThat(quote.get("payable").decimalValue()).isEqualByComparingTo("0");
        assertThat(creditNotes.count()).isZero();

        String waiting = raise(acme, usersRequest(5)).get("id").asString();
        JsonNode order = json(POST(path(), superToken, body(addOnId, null, "Not needed any more")).andExpect(status().isOk()));
        assertThat(order.get("status").asString()).isEqualTo("ACTIVATED");
        assertThat(order.get("creditNoteIds")).hasSize(1);
        JsonNode note = json(GET("/api/billing/credit-notes/" + order.at("/creditNoteIds/0").asString(), amToken));
        assertThat(note.get("reason").asString()).isEqualTo("Add-on cancelled");
        assertThat(note.get("invoiceNumber").asString()).isEqualTo("INV/2026-27/000002");
        assertThat(note.get("total").decimalValue()).isEqualByComparingTo("182.71");

        assertThat(sub().getCurrent().getAddOns()).isEmpty();
        assertThat(sub().getCreditBalance()).isEqualByComparingTo("182.71");
        assertThat(entitlements.allows(acme.getId(), "automation:view")).isFalse();
        GET("/api/billing/orders/" + waiting, amToken).andExpect(jsonPath("$.status").value("SUPERSEDED"));
        // it is gone, so it cannot be cancelled again
        POST(path(), superToken, body(addOnId, null, "Again")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.addOnId").exists());
    }

    @Test
    void staffMayCreditADifferentAmountUpToWhatWasPaid() throws Exception {
        POST(path() + "/quote", superToken, body(addOnId, 203.24, null)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.creditTaxable").exists());
        POST(path(), superToken, body(addOnId, -1, "x")).andExpect(status().isBadRequest());
        JsonNode order = json(POST(path(), superToken, body(addOnId, 203.23, "Never worked for them")).andExpect(status().isOk()));
        assertThat(order.at("/credits/0/taxable").decimalValue()).isEqualByComparingTo("203.23");
        assertThat(order.at("/credits/0/total").decimalValue()).isEqualByComparingTo("239.81");
        assertThat(sub().getCreditBalance()).isEqualByComparingTo("239.81");
    }

    @Test
    void anAddOnCanBeCancelledWithoutCredit() throws Exception {
        JsonNode order = json(POST(path(), superToken, body(addOnId, 0, "Their choice")).andExpect(status().isOk()));
        assertThat(order.get("credits")).isEmpty();
        assertThat(creditNotes.count()).isZero();
        assertThat(sub().getCurrent().getAddOns()).isEmpty();
    }

    @Test
    void cancellingThePlanEndsItTodayWithoutGrace() throws Exception {
        JsonNode quote = json(POST(path() + "/quote", superToken, body(null, null, null)).andExpect(status().isOk()));
        assertThat(quote.get("kind").asString()).isEqualTo("CANCEL_PLAN");
        assertThat(quote.get("credits")).hasSize(2);
        assertThat(quote.get("creditFromNotes").decimalValue()).isEqualByComparingTo("730.84");

        JsonNode order = json(POST(path(), superToken, body(null, null, "Closing the account")).andExpect(status().isOk()));
        assertThat(order.get("creditNoteIds")).hasSize(2);
        Subscription s = sub();
        assertThat(s.isCancelled()).isTrue();
        assertThat(s.getCurrent().getTermEnd()).isEqualTo(Instant.parse("2026-12-15T18:30:00Z"));
        assertThat(s.getNext()).isNull();
        assertThat(s.getCreditBalance()).isEqualByComparingTo("730.84");
        GET("/api/billing/customers/" + acme.getId() + "/subscription", amToken)
                .andExpect(jsonPath("$.state").value("EXPIRED")).andExpect(jsonPath("$.readOnly").value(true));
        POST("/api/workspaces/" + ws.getId() + "/teams", adminToken, Map.of("name", "Night")).andExpect(status().isPaymentRequired());
        POST(path(), superToken, body(null, null, "Again")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.addOnId").exists());

        // buying a plan again brings the customer back, and the credit pays part of it
        JsonNode again = raise(acme, request("NEW", "gold-monthly"));
        assertThat(again.get("accountCreditUsed").decimalValue()).isEqualByComparingTo("730.84");
        assertThat(again.get("payable").decimalValue()).isEqualByComparingTo("331.16");
        pay(again.get("id").asString()).andExpect(status().isOk());
        assertThat(sub().isCancelled()).isFalse();
        assertThat(sub().getCreditBalance()).isEqualByComparingTo("0");
        GET("/api/billing/customers/" + acme.getId() + "/subscription", amToken)
                .andExpect(jsonPath("$.state").value("ACTIVE")).andExpect(jsonPath("$.readOnly").value(false));
    }

    @Test
    void cancellingNeedsAReasonAndTheRightToRefund() throws Exception {
        POST(path(), amToken, body(addOnId, null, "x")).andExpect(status().isForbidden());
        POST(path() + "/quote", amToken, body(addOnId, null, null)).andExpect(status().isForbidden());
        POST(path(), superToken, body(addOnId, null, null)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.reason").exists());
        POST(path(), superToken, body("no-such-add-on", null, "x")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.addOnId").exists());
        assertThat(sub().getCurrent().getAddOns()).hasSize(1);
    }
}
