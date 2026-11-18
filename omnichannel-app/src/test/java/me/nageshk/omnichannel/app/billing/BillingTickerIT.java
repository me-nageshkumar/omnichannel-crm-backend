package me.nageshk.omnichannel.app.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import me.nageshk.omnichannel.common.billing.OrderStatus;
import me.nageshk.omnichannel.db.billing.BillingLock;
import me.nageshk.omnichannel.db.billing.BillingOrder;
import me.nageshk.omnichannel.db.billing.BillingOrderRepository;
import me.nageshk.omnichannel.db.billing.OrderPayment;
import me.nageshk.omnichannel.db.billing.Subscription;
import me.nageshk.omnichannel.db.billing.SubscriptionRepository;
import me.nageshk.omnichannel.service.billing.BillingTicker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import tools.jackson.databind.JsonNode;

/** The background pass: finishes what was interrupted, expires old quotes, and rolls a paid renewal in. */
class BillingTickerIT extends MoneyApiTestBase {

    static final Instant JAN_1_MIDNIGHT = Instant.parse("2026-12-31T18:30:00Z");

    @Autowired BillingTicker ticker;
    @Autowired BillingOrderRepository orders;
    @Autowired SubscriptionRepository subscriptions;
    @Autowired MongoTemplate mongo;
    @Autowired me.nageshk.omnichannel.service.billing.wallet.WalletService walletService;

    /** An order whose payment was recorded and whose server then stopped. */
    private String interrupted() throws Exception {
        String id = raise(acme, request("NEW", "gold-monthly")).get("id").asString();
        BillingOrder o = orders.findById(id).orElseThrow();
        o.setStatus(OrderStatus.ACTIVATING);
        o.setPayment(new OrderPayment("UPI", "UTR-1", LocalDate.of(2026, 12, 1), null, amUser.getId(), clock.instant()));
        o.setActivatingSince(clock.instant());
        orders.save(o);
        return id;
    }

    private OrderStatus statusOf(String id) {
        return orders.findById(id).orElseThrow().getStatus();
    }

    @Test
    void anInterruptedActivationIsFinishedAfterAMinute() throws Exception {
        String id = interrupted();
        clock.advance(Duration.ofSeconds(30));
        ticker.tick();
        assertThat(statusOf(id)).isEqualTo(OrderStatus.ACTIVATING);
        assertThat(subscriptions.findByCustomerId(acme.getId())).isEmpty();

        clock.advance(Duration.ofSeconds(31));
        ticker.tick();
        assertThat(statusOf(id)).isEqualTo(OrderStatus.ACTIVATED);
        Subscription s = subscriptions.findByCustomerId(acme.getId()).orElseThrow();
        assertThat(s.getCurrent().getPlanCode()).isEqualTo("gold-monthly");
        assertThat(s.getRevision()).isEqualTo(1);
        tokens();
        GET("/api/billing/orders/" + id, amToken).andExpect(jsonPath("$.invoiceNumber").value("INV/26-27/000001"));
    }

    @Test
    void aCustomerBeingChangedIsLeftForTheNextTick() throws Exception {
        String id = interrupted();
        clock.advance(Duration.ofSeconds(61));
        BillingLock held = new BillingLock();
        held.setId(acme.getId());
        held.setHolder("another-server");
        held.setLockedUntil(clock.instant().plusSeconds(30));
        mongo.save(held);

        ticker.tick();
        assertThat(statusOf(id)).isEqualTo(OrderStatus.ACTIVATING);
        clock.advance(Duration.ofSeconds(31));
        ticker.tick();
        assertThat(statusOf(id)).isEqualTo(OrderStatus.ACTIVATED);
    }

    @Test
    void anOldRequestIsMarkedExpired() throws Exception {
        String old = raise(acme, request("NEW", "gold-monthly")).get("id").asString();
        on(day(2026, 12, 8));
        String fresh = raise(bolt, request("NEW", "gold-monthly")).get("id").asString();
        on(day(2026, 12, 9));
        ticker.tick();
        assertThat(statusOf(old)).isEqualTo(OrderStatus.EXPIRED);
        assertThat(statusOf(fresh)).isEqualTo(OrderStatus.REQUESTED);
    }

    @Test
    void aPaidRenewalBecomesTheCurrentTermWhenItStarts() throws Exception {
        buy(acme, request("NEW", "gold-monthly"));
        on(day(2026, 12, 20));
        buy(acme, request("RENEWAL", null));
        ticker.tick();
        assertThat(subscriptions.findByCustomerId(acme.getId()).orElseThrow().getNext()).isNotNull();

        on(day(2027, 1, 1));
        // the customer is covered across midnight whether or not the ticker has run
        String path = "/api/billing/customers/" + acme.getId() + "/subscription";
        GET(path, amToken).andExpect(jsonPath("$.state").value("ACTIVE"))
                .andExpect(jsonPath("$.termStart").value("2026-12-31T18:30:00Z"));

        ticker.tick();
        Subscription s = subscriptions.findByCustomerId(acme.getId()).orElseThrow();
        assertThat(s.getNext()).isNull();
        assertThat(s.getCurrent().getTermStart()).isEqualTo(JAN_1_MIDNIGHT);
        assertThat(s.getCurrent().getTermEnd()).isEqualTo(Instant.parse("2027-01-31T18:30:00Z"));
        assertThat(s.getCurrent().getPlanPaid()).isEqualByComparingTo("900.00");
        assertThat(s.getCurrent().getPlanInvoiceId()).isNotBlank();
        assertThat(s.getRevision()).isEqualTo(2);
        Long version = s.getVersion();

        ticker.tick();
        Subscription again = subscriptions.findByCustomerId(acme.getId()).orElseThrow();
        assertThat(again.getVersion()).isEqualTo(version);
        assertThat(again.getCurrent().getTermStart()).isEqualTo(JAN_1_MIDNIGHT);
        GET(path, amToken).andExpect(jsonPath("$.state").value("ACTIVE"));
        // and it can be saved by the usual code afterwards
        buy(acme, usersRequest(5));
    }

    @Test
    void aRenewalThatHasBegunIsCreditedOnlyForItsUnusedDays() throws Exception {
        buy(acme, request("NEW", "gold-monthly"));
        on(day(2026, 12, 20));
        buy(acme, request("RENEWAL", null));
        // 5 Jan, and the ticker has not moved the renewal in yet
        on(day(2027, 1, 5));
        JsonNode quote = json(POST(quotesPath(acme), amToken, request("UPGRADE", "platinum-monthly")).andExpect(status().isOk()));
        assertThat(quote.get("credits")).hasSize(1);
        assertThat(quote.at("/credits/0/invoiceNumber").asString()).isEqualTo("INV/26-27/000002");
        // 900 for 31 days, 27 unused
        assertThat(quote.at("/credits/0/taxable").decimalValue()).isEqualByComparingTo("783.87");
    }

    private org.bson.Document walletDoc() {
        return mongo.getCollection("wallets").find(new org.bson.Document("customerId", acme.getId())).first();
    }

    /** A wallet whose one ledger entry was never copied out, as a server that stopped mid-way leaves it. */
    private void walletWithAPendingEntry() {
        walletService.credit(acme.getId(), new java.math.BigDecimal("1000"), new me.nageshk.omnichannel.db.billing.WalletEntry() {{
            setType(me.nageshk.omnichannel.common.billing.WalletEntryType.ADJUSTMENT);
        }});
        org.bson.Document entry = mongo.getCollection("walletEntries").find().first();
        mongo.getCollection("walletEntries").deleteMany(new org.bson.Document());
        mongo.getCollection("wallets").updateOne(new org.bson.Document("customerId", acme.getId()),
                new org.bson.Document("$push", new org.bson.Document("pending", entry)));
        assertThat(walletDoc().getList("pending", org.bson.Document.class)).hasSize(1);
    }

    @Test
    void aLedgerEntryLeftOnTheWalletIsMovedAfterAMinute() {
        walletWithAPendingEntry();
        clock.advance(Duration.ofSeconds(30));
        ticker.tick();
        assertThat(walletDoc().getList("pending", org.bson.Document.class)).hasSize(1);
        assertThat(mongo.getCollection("walletEntries").countDocuments()).isZero();

        clock.advance(Duration.ofSeconds(31));
        ticker.tick();
        assertThat(walletDoc().getList("pending", org.bson.Document.class)).isEmpty();
        assertThat(mongo.getCollection("walletEntries").countDocuments()).isEqualTo(1);
        assertThat(walletDoc().get("balance").toString()).isEqualTo("1000.0000");
    }
}
