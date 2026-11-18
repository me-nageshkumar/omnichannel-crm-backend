package me.nageshk.omnichannel.app.inbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import me.nageshk.omnichannel.app.support.FakeDevice;
import me.nageshk.omnichannel.app.support.TestBilling;
import me.nageshk.omnichannel.common.billing.BillingPeriod;
import me.nageshk.omnichannel.common.billing.UsageMeter;
import me.nageshk.omnichannel.common.billing.UsageSource;
import me.nageshk.omnichannel.common.billing.WalletEntryType;
import me.nageshk.omnichannel.db.billing.PlanTerms;
import me.nageshk.omnichannel.db.billing.Subscription;
import me.nageshk.omnichannel.db.billing.SubscriptionRepository;
import me.nageshk.omnichannel.db.billing.WalletEntry;
import me.nageshk.omnichannel.db.billing.WalletEntryRepository;
import me.nageshk.omnichannel.db.billing.WalletRepository;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import me.nageshk.omnichannel.security.entitlement.Entitlements;
import me.nageshk.omnichannel.service.rpc.Envelope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** A message the server sends is charged to the customer's wallet; one the phone refuses is not. */
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "omnichannel.rpc.timeout=2s")
class SendChargingIT extends InboxApiTestBase {

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired TestBilling billing;
    @Autowired SubscriptionRepository subscriptions;
    @Autowired WalletRepository wallets;
    @Autowired WalletEntryRepository entries;
    @Autowired Entitlements entitlements;

    @AfterEach
    void noBilling() {
        billing.reset();
    }

    /** Puts Acme on a plan that charges this much a message (null: a plan that charges nothing). */
    private void plan(String rate) {
        Subscription s = billing.assign(acme.getId(), Set.of(), Map.of());
        s.getCurrent().setTerms(new PlanTerms("Gold", BillingPeriod.MONTHLY, 2, null, List.of(), List.of(), List.of(), Map.of(),
                rate == null ? Map.of() : Map.of(UsageMeter.WHATSAPP_MESSAGE, new BigDecimal(rate))));
        subscriptions.save(s);
        entitlements.evict(acme.getId());
    }

    private BigDecimal balance() {
        return wallets.findByCustomerId(acme.getId()).orElseThrow().getBalance();
    }

    private List<WalletEntry> ledger() {
        return entries.findByCustomerIdOrderByAtDescIdDesc(acme.getId());
    }

    private ResponseEntity<JsonNode> post(WaWebChat chat, Object body) throws Exception {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(managerToken);
        h.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(inbox() + "/chats/" + chat.getId() + "/messages", HttpMethod.POST,
                new HttpEntity<>(mapper.writeValueAsString(body), h), JsonNode.class);
    }

    private ObjectNode model(WaWebChat chat, String serialized, String body) {
        ObjectNode m = mapper.createObjectNode();
        m.set("id", mapper.createObjectNode().put("_serialized", serialized).put("fromMe", true).put("remote", chat.getRemoteId()));
        m.put("fromMe", true).put("body", body).put("type", "chat").put("ack", 1).put("timestamp", Instant.now().getEpochSecond());
        return m;
    }

    private FakeDevice device() throws Exception {
        FakeDevice device = FakeDevice.connectAndAuth(port, testDevices.redeem(number).deviceKey());
        device.send(Envelope.event("ready", mapper.createObjectNode().put("pushname", "N").put("wid", number.getNumber() + "@c.us")));
        testDevices.awaitReady(number);
        return device;
    }

    /** Posts a text and lets {@code answer} speak for the phone; null means the phone says nothing. */
    private ResponseEntity<JsonNode> send(FakeDevice device, WaWebChat chat, String text, Function<Envelope, Envelope> answer)
            throws Exception {
        CompletableFuture<ResponseEntity<JsonNode>> http = CompletableFuture.supplyAsync(() -> {
            try {
                return post(chat, Map.of("text", text));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        Envelope req = device.next();
        if (answer != null) device.send(answer.apply(req));
        return http.get(10, TimeUnit.SECONDS);
    }

    @Test
    void eachMessageSentIsChargedAtThePlansRate() throws Exception {
        plan("0.25");
        billing.wallet(acme.getId(), "1000.0000");
        WaWebChat chat = chat("Aarav", "919812345678", 5);
        try (FakeDevice device = device()) {
            for (int i = 1; i <= 3; i++) {
                String key = "true_" + chat.getRemoteId() + "_SENT" + i;
                ResponseEntity<JsonNode> res = send(device, chat, "hello " + i, req -> Envelope.response(req.id(), model(chat, key, "hello")));
                assertThat(res.getStatusCode().is2xxSuccessful()).isTrue();
            }
        }
        assertThat(balance()).isEqualByComparingTo("999.25");
        assertThat(ledger()).hasSize(3).allSatisfy(e -> {
            assertThat(e.getType()).isEqualTo(WalletEntryType.USAGE);
            assertThat(e.getAmount()).isEqualByComparingTo("-0.25");
            assertThat(e.getSource()).isEqualTo(UsageSource.CONSOLE);
            assertThat(e.getWorkspaceId()).isEqualTo(brand.getId());
            assertThat(e.getMeter()).isEqualTo(UsageMeter.WHATSAPP_MESSAGE);
        });
        // each stored message says which entry paid for it
        List<String> paidBy = messages.findAll().stream().filter(m -> chat.getId().equals(m.getChatId()) && m.getUsageEntryId() != null)
                .map(m -> m.getUsageEntryId()).toList();
        assertThat(paidBy).hasSize(3).containsExactlyInAnyOrderElementsOf(ledger().stream().map(WalletEntry::getId).toList());
    }

    @Test
    void aMessageThePhoneRefusesIsGivenBack() throws Exception {
        plan("0.25");
        billing.wallet(acme.getId(), "1000.0000");
        WaWebChat chat = chat("Aarav", "919812345678", 5);
        try (FakeDevice device = device()) {
            ResponseEntity<JsonNode> res = send(device, chat, "hello", req -> Envelope.errorResponse(req.id(), "SEND_FAILED", "no"));
            assertThat(res.getStatusCode().is2xxSuccessful()).isFalse();
        }
        assertThat(balance()).isEqualByComparingTo("1000");
        assertThat(ledger()).hasSize(2);
        WalletEntry back = ledger().stream().filter(e -> e.getType() == WalletEntryType.REVERSAL).findFirst().orElseThrow();
        WalletEntry charge = ledger().stream().filter(e -> e.getType() == WalletEntryType.USAGE).findFirst().orElseThrow();
        assertThat(back.getAmount()).isEqualByComparingTo("0.25");
        assertThat(back.getRef()).isEqualTo(charge.getId());
        assertThat(back.getSource()).isEqualTo(UsageSource.CONSOLE);
    }

    @Test
    void aSendWhoseOutcomeIsUnknownStaysCharged() throws Exception {
        plan("0.25");
        billing.wallet(acme.getId(), "1000.0000");
        WaWebChat chat = chat("Aarav", "919812345678", 5);
        try (FakeDevice device = device()) {
            // the phone never answers: the message may have gone
            ResponseEntity<JsonNode> res = send(device, chat, "hello", null);
            assertThat(res.getStatusCode().is2xxSuccessful()).isFalse();
        }
        assertThat(balance()).isEqualByComparingTo("999.75");
        assertThat(ledger()).singleElement().matches(e -> e.getType() == WalletEntryType.USAGE);
    }

    @Test
    void anEmptyWalletStopsTheSendBeforeItReachesThePhone() throws Exception {
        plan("0.25");
        billing.wallet(acme.getId(), "0.2000");
        WaWebChat chat = chat("Aarav", "919812345678", 5);
        try (FakeDevice device = device()) {
            ResponseEntity<JsonNode> res = post(chat, Map.of("text", "hello"));
            assertThat(res.getStatusCode().value()).isEqualTo(402);
            assertThat(res.getBody().get("code").asString()).isEqualTo("WALLET_EMPTY");
            assertThat(device.poll(java.time.Duration.ofMillis(300))).isEmpty();
        }
        assertThat(balance()).isEqualByComparingTo("0.2");
        assertThat(ledger()).isEmpty();
        assertThat(messages.findAll().stream().filter(m -> chat.getId().equals(m.getChatId()) && "hello".equals(m.getBody()))).isEmpty();
    }

    @Test
    void aMessageThatIsNotValidCostsNothing() throws Exception {
        plan("0.25");
        billing.wallet(acme.getId(), "1000.0000");
        WaWebChat chat = chat("Aarav", "919812345678", 5);
        try (FakeDevice device = device()) {
            ResponseEntity<JsonNode> res = post(chat, Map.of("text", ""));
            assertThat(res.getStatusCode().value()).isEqualTo(400);
        }
        assertThat(balance()).isEqualByComparingTo("1000");
        assertThat(ledger()).isEmpty();
    }

    @Test
    void aPlanWithoutARateAndACustomerWithoutAPlanSendFreely() throws Exception {
        WaWebChat chat = chat("Aarav", "919812345678", 5);
        try (FakeDevice device = device()) {
            String key = "true_" + chat.getRemoteId() + "_FREE1";
            assertThat(send(device, chat, "no plan", req -> Envelope.response(req.id(), model(chat, key, "no plan")))
                    .getStatusCode().is2xxSuccessful()).isTrue();
            plan(null);
            String key2 = "true_" + chat.getRemoteId() + "_FREE2";
            assertThat(send(device, chat, "no rate", req -> Envelope.response(req.id(), model(chat, key2, "no rate")))
                    .getStatusCode().is2xxSuccessful()).isTrue();
        }
        assertThat(wallets.findByCustomerId(acme.getId())).isEmpty();
        assertThat(ledger()).isEmpty();
    }

    @Test
    void anErrorInsideThePageMayHaveComeAfterTheMessageLeftSoItStaysCharged() throws Exception {
        plan("0.25");
        billing.wallet(acme.getId(), "1000.0000");
        WaWebChat chat = chat("Aarav", "919812345678", 5);
        try (FakeDevice device = device()) {
            ResponseEntity<JsonNode> res = send(device, chat, "hello", req -> Envelope.errorResponse(req.id(), "PAGE_ERROR", "boom"));
            assertThat(res.getStatusCode().is2xxSuccessful()).isFalse();
            // the page was not ready: nothing was tried, so this one comes back
            res = send(device, chat, "hello again", req -> Envelope.errorResponse(req.id(), "WHATSAPP_NOT_READY", "no"));
            assertThat(res.getStatusCode().is2xxSuccessful()).isFalse();
        }
        assertThat(balance()).isEqualByComparingTo("999.75");
        assertThat(ledger().stream().filter(e -> e.getType() == WalletEntryType.REVERSAL)).hasSize(1);
    }

    @Test
    void aPhoneThatDropsOffMidSendLeavesTheMessageCharged() throws Exception {
        plan("0.25");
        billing.wallet(acme.getId(), "1000.0000");
        WaWebChat chat = chat("Aarav", "919812345678", 5);
        FakeDevice device = device();
        CompletableFuture<ResponseEntity<JsonNode>> http = CompletableFuture.supplyAsync(() -> {
            try {
                return post(chat, Map.of("text", "hello"));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        device.next();
        // the message had left the server; whether the phone sent it before it dropped is not known
        device.close();
        assertThat(http.get(10, TimeUnit.SECONDS).getStatusCode().is2xxSuccessful()).isFalse();
        assertThat(balance()).isEqualByComparingTo("999.75");
        assertThat(ledger()).singleElement().matches(e -> e.getType() == WalletEntryType.USAGE);
    }

    @Test
    void aChargeGivenBackRemembersWhenItWasCharged() throws Exception {
        plan("0.25");
        billing.wallet(acme.getId(), "1000.0000");
        WaWebChat chat = chat("Aarav", "919812345678", 5);
        try (FakeDevice device = device()) {
            send(device, chat, "hello", req -> Envelope.errorResponse(req.id(), "SEND_FAILED", "no"));
        }
        WalletEntry back = ledger().stream().filter(e -> e.getType() == WalletEntryType.REVERSAL).findFirst().orElseThrow();
        WalletEntry charge = ledger().stream().filter(e -> e.getType() == WalletEntryType.USAGE).findFirst().orElseThrow();
        assertThat(back.getUsageAt()).isNotNull();
        // to the instant: a charge taken at midnight and given back must come off the same day it went on
        assertThat(back.getUsageAt()).isEqualTo(charge.getAt());
    }
}
