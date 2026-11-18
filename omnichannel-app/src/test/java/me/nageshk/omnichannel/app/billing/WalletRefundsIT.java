package me.nageshk.omnichannel.app.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import me.nageshk.omnichannel.common.billing.OrderKind;
import me.nageshk.omnichannel.common.billing.OrderStatus;
import me.nageshk.omnichannel.db.billing.BillingOrder;
import me.nageshk.omnichannel.db.billing.BillingOrderRepository;
import me.nageshk.omnichannel.db.billing.CreditNoteRepository;
import me.nageshk.omnichannel.db.billing.SubscriptionRepository;
import me.nageshk.omnichannel.db.billing.WalletRepository;
import me.nageshk.omnichannel.db.tenant.Customer;
import me.nageshk.omnichannel.service.billing.order.OrderActivator;
import me.nageshk.omnichannel.service.billing.order.OrderSteps;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;

/** Taking top-up money back out of the wallet: it leaves the wallet and returns as credit, with its GST. */
class WalletRefundsIT extends MoneyApiTestBase {

    @Autowired WalletRepository wallets;
    @Autowired SubscriptionRepository subscriptions;
    @Autowired CreditNoteRepository creditNotes;
    @Autowired BillingOrderRepository orders;
    @Autowired OrderActivator activator;
    @MockitoSpyBean OrderSteps steps;

    private String path(Customer c, String tail) {
        return "/api/billing/customers/" + c.getId() + "/wallet" + tail;
    }

    private BigDecimal balance(Customer c) {
        return wallets.findByCustomerId(c.getId()).orElseThrow().getBalance();
    }

    private BigDecimal credit(Customer c) {
        return subscriptions.findByCustomerId(c.getId()).orElseThrow().getCreditBalance();
    }

    private void goldWithTopUp(Customer c, int amount) throws Exception {
        buy(c, request("NEW", "gold-monthly"));
        buy(c, topUp(amount));
    }

    @Test
    void aWalletRefundCreditsTheTopUpInvoiceWithItsGst() throws Exception {
        goldWithTopUp(acme, 1000);
        GET(path(acme, ""), amToken).andExpect(jsonPath("$.refundable").value(1000.0));
        JsonNode q = json(POST(path(acme, "/refunds/quote"), superToken, Map.of("amount", 400)).andExpect(status().isOk()));
        assertThat(q.get("kind").asString()).isEqualTo("WALLET_REFUND");
        assertThat(q.get("credits")).hasSize(1);
        assertThat(q.at("/credits/0/invoiceNumber").asString()).isEqualTo("INV/26-27/000002");
        assertThat(q.at("/credits/0/taxable").decimalValue()).isEqualByComparingTo("400.00");
        assertThat(q.at("/credits/0/tax/cgst").decimalValue()).isEqualByComparingTo("36.00");
        assertThat(q.at("/credits/0/total").decimalValue()).isEqualByComparingTo("472.00");
        assertThat(q.get("creditFromNotes").decimalValue()).isEqualByComparingTo("472.00");
        assertThat(balance(acme)).isEqualByComparingTo("1000");

        JsonNode order = json(POST(path(acme, "/refunds"), superToken, Map.of("amount", 400, "reason", "Closing the wallet down"))
                .andExpect(status().isOk()));
        assertThat(order.get("status").asString()).isEqualTo("ACTIVATED");
        assertThat(order.get("creditNoteIds")).hasSize(1);
        assertThat(balance(acme)).isEqualByComparingTo("600");
        assertThat(credit(acme)).isEqualByComparingTo("472.00");
        GET("/api/billing/credit-notes/" + order.at("/creditNoteIds/0").asString(), amToken)
                .andExpect(jsonPath("$.reason").value("Wallet refunded")).andExpect(jsonPath("$.total").value(472.00));
        GET(path(acme, ""), amToken).andExpect(jsonPath("$.refundable").value(600.0));
        JsonNode entries = json(GET(path(acme, "/entries"), amToken));
        assertThat(entries.at("/items/0/type").asString()).isEqualTo("REFUND");
        assertThat(entries.at("/items/0/amount").decimalValue()).isEqualByComparingTo("-400");
    }

    @Test
    void onlyTopUpMoneyStillInTheWalletCanComeBack() throws Exception {
        goldWithTopUp(acme, 1000);
        POST(path(acme, "/adjustments"), amToken, Map.of("amount", 500, "note", "Goodwill")).andExpect(status().isOk());
        // the wallet holds 1500, but only 1000 was paid for
        GET(path(acme, ""), amToken).andExpect(jsonPath("$.refundable").value(1000.0));
        POST(path(acme, "/refunds"), superToken, Map.of("amount", 1200, "reason", "x")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOT_ENOUGH_WALLET"));
        // and once usage has taken the wallet down, no more than is left in it
        POST(path(acme, "/adjustments"), amToken, Map.of("amount", -1200, "note", "as if used")).andExpect(status().isOk());
        GET(path(acme, ""), amToken).andExpect(jsonPath("$.refundable").value(300.0));
        POST(path(acme, "/refunds/quote"), superToken, Map.of("amount", 400)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOT_ENOUGH_WALLET"));
        assertThat(balance(acme)).isEqualByComparingTo("300");
        assertThat(creditNotes.count()).isZero();
        assertThat(orders.findAll()).noneMatch(o -> o.getKind() == OrderKind.WALLET_REFUND);
    }

    @Test
    void aRefundAcrossTwoTopUpsCreditsTheNewestFirst() throws Exception {
        goldWithTopUp(acme, 1000);
        clock.advance(java.time.Duration.ofMinutes(5));
        buy(acme, topUp(500));
        JsonNode q = json(POST(path(acme, "/refunds/quote"), superToken, Map.of("amount", 700)).andExpect(status().isOk()));
        assertThat(q.get("credits")).hasSize(2);
        assertThat(q.at("/credits/0/invoiceNumber").asString()).isEqualTo("INV/26-27/000003");
        assertThat(q.at("/credits/0/taxable").decimalValue()).isEqualByComparingTo("500.00");
        assertThat(q.at("/credits/1/invoiceNumber").asString()).isEqualTo("INV/26-27/000002");
        assertThat(q.at("/credits/1/taxable").decimalValue()).isEqualByComparingTo("200.00");
    }

    @Test
    void aRefundInterruptedAfterTheDebitIsFinishedOnce() throws Exception {
        goldWithTopUp(acme, 1000);
        doThrow(new IllegalStateException("stopped")).when(steps).creditNotes(any());
        assertThatThrownBy(() -> POST(path(acme, "/refunds"), superToken, Map.of("amount", 400, "reason", "x")))
                .hasRootCauseMessage("stopped");
        // the money has left the wallet, so the order is not thrown away
        assertThat(balance(acme)).isEqualByComparingTo("600");
        List<BillingOrder> refunds = orders.findAll().stream().filter(o -> o.getKind() == OrderKind.WALLET_REFUND).toList();
        assertThat(refunds).singleElement().matches(o -> o.getStatus() == OrderStatus.ACTIVATING);

        doCallRealMethod().when(steps).creditNotes(any());
        activator.finishPending(acme.getId());
        activator.finishPending(acme.getId());
        assertThat(orders.findById(refunds.get(0).getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.ACTIVATED);
        assertThat(balance(acme)).isEqualByComparingTo("600");
        assertThat(creditNotes.count()).isEqualTo(1);
        assertThat(credit(acme)).isEqualByComparingTo("472.00");
    }

    @Test
    void theRefundCarriesTheInvoicesTaxAndNeedsTheRightToRefund() throws Exception {
        goldWithTopUp(bolt, 1000);
        POST(path(bolt, "/refunds/quote"), superToken, Map.of("amount", 400)).andExpect(status().isOk())
                .andExpect(jsonPath("$.credits[0].tax.igst").value(72.00)).andExpect(jsonPath("$.credits[0].tax.cgst").value(0.0));
        POST(path(bolt, "/refunds"), amToken, Map.of("amount", 400, "reason", "x")).andExpect(status().isForbidden());
        POST(path(bolt, "/refunds/quote"), amToken, Map.of("amount", 400)).andExpect(status().isForbidden());
        POST(path(bolt, "/refunds"), superToken, Map.of("amount", 400)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.reason").exists());
        POST(path(bolt, "/refunds"), superToken, Map.of("amount", 0, "reason", "x")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.amount").exists());
        POST(path(bolt, "/refunds"), superToken, Map.of("amount", 10.005, "reason", "x")).andExpect(status().isBadRequest());
        assertThat(balance(bolt)).isEqualByComparingTo("1000");
    }
}
