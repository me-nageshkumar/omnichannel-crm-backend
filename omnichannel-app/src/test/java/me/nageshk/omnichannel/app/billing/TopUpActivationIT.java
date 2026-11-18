package me.nageshk.omnichannel.app.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import me.nageshk.omnichannel.common.billing.OrderKind;
import me.nageshk.omnichannel.common.billing.OrderStatus;
import me.nageshk.omnichannel.common.billing.WalletEntryType;
import me.nageshk.omnichannel.db.billing.BillingOrderRepository;
import me.nageshk.omnichannel.db.billing.Invoice;
import me.nageshk.omnichannel.db.billing.InvoiceRepository;
import me.nageshk.omnichannel.db.billing.SubscriptionRepository;
import me.nageshk.omnichannel.db.billing.Wallet;
import me.nageshk.omnichannel.db.billing.WalletEntry;
import me.nageshk.omnichannel.db.billing.WalletEntryRepository;
import me.nageshk.omnichannel.db.billing.WalletRepository;
import me.nageshk.omnichannel.service.billing.order.OrderActivator;
import me.nageshk.omnichannel.service.billing.order.OrderSteps;
import me.nageshk.omnichannel.service.billing.wallet.WalletMover;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;

/** Recording a top-up's payment puts the money in the wallet, once. */
class TopUpActivationIT extends MoneyApiTestBase {

    @Autowired WalletRepository wallets;
    @Autowired WalletEntryRepository entries;
    @Autowired InvoiceRepository invoices;
    @Autowired BillingOrderRepository orders;
    @Autowired SubscriptionRepository subscriptions;
    @Autowired OrderActivator activator;
    @Autowired WalletMover mover;
    @MockitoSpyBean OrderSteps steps;

    private Wallet wallet() {
        return wallets.findByCustomerId(acme.getId()).orElseThrow();
    }

    private List<WalletEntry> ledger() {
        return entries.findByCustomerIdOrderByAtDescIdDesc(acme.getId());
    }

    @Test
    void aPaidTopUpCreditsTheWalletAndIsInvoiced() throws Exception {
        buy(acme, request("NEW", "gold-monthly"));
        JsonNode paid = buy(acme, topUp(1000));
        assertThat(paid.get("status").asString()).isEqualTo("ACTIVATED");
        assertThat(paid.get("number").asString()).isEqualTo("ORD-000002");

        assertThat(wallet().getBalance()).isEqualByComparingTo("1000");
        assertThat(ledger()).singleElement().satisfies(e -> {
            assertThat(e.getType()).isEqualTo(WalletEntryType.TOPUP);
            assertThat(e.getAmount()).isEqualByComparingTo("1000");
            assertThat(e.getRef()).isEqualTo("ORD-000002");
            assertThat(e.getBy()).isEqualTo(amUser.getId());
        });
        Invoice inv = invoices.findById(paid.get("invoiceId").asString()).orElseThrow();
        assertThat(inv.getOrderKind()).isEqualTo(OrderKind.TOPUP);
        assertThat(inv.getLines()).singleElement().satisfies(l -> {
            assertThat(l.description()).isEqualTo("Wallet top-up");
            assertThat(l.amount()).isEqualByComparingTo("1000.00");
        });
        assertThat(inv.getTotal()).isEqualByComparingTo("1180.00");
        // the plan is as it was
        assertThat(subscriptions.findByCustomerId(acme.getId()).orElseThrow().getRevision()).isEqualTo(1);
        JsonNode events = json(GET("/api/billing/customers/" + acme.getId() + "/subscription/events", amToken));
        List<String> summaries = new ArrayList<>();
        events.forEach(e -> summaries.add(e.get("summary").asString()));
        assertThat(summaries).anyMatch(s -> s.contains("ORD-000002") && s.contains("Wallet top-up"));
        GET("/api/my/billing/wallet", adminToken).andExpect(status().isOk());
    }

    @Test
    void aTopUpInterruptedAfterTheCreditIsFinishedWithoutCreditingAgain() throws Exception {
        buy(acme, request("NEW", "gold-monthly"));
        String id = raise(acme, topUp(1000)).get("id").asString();
        doThrow(new IllegalStateException("stopped")).when(steps).invoice(any());
        assertThatThrownBy(() -> pay(id)).hasRootCauseMessage("stopped");
        assertThat(orders.findById(id).orElseThrow().getStatus()).isEqualTo(OrderStatus.ACTIVATING);
        assertThat(wallet().getBalance()).isEqualByComparingTo("1000");

        // even with the ledger entry moved out of the wallet, and the wallet step run again from scratch
        mover.move(acme.getId());
        var stored = orders.findById(id).orElseThrow();
        stored.getSteps().remove("wallet");
        orders.save(stored);
        doCallRealMethod().when(steps).invoice(any());
        activator.finishPending(acme.getId());
        assertThat(orders.findById(id).orElseThrow().getStatus()).isEqualTo(OrderStatus.ACTIVATED);
        assertThat(wallet().getBalance()).isEqualByComparingTo("1000");
        assertThat(ledger()).hasSize(1);
        assertThat(invoices.count()).isEqualTo(2);
    }

    @Test
    void aTopUpIsCreditedEvenIfThePlanWasCancelledMeanwhile() throws Exception {
        buy(acme, request("NEW", "gold-monthly"));
        String id = raise(acme, topUp(500)).get("id").asString();
        POST("/api/billing/customers/" + acme.getId() + "/cancellations", superToken, Map.of("reason", "Closing"))
                .andExpect(status().isOk());
        assertThat(orders.findById(id).orElseThrow().getStatus()).isEqualTo(OrderStatus.REQUESTED);
        pay(id).andExpect(status().isOk());
        assertThat(wallet().getBalance()).isEqualByComparingTo("500");
    }
}
