package me.nageshk.omnichannel.app.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import me.nageshk.omnichannel.common.billing.BillingPeriod;
import me.nageshk.omnichannel.common.billing.LimitKey;
import me.nageshk.omnichannel.common.billing.UsageMeter;
import me.nageshk.omnichannel.common.billing.UsageSource;
import me.nageshk.omnichannel.common.billing.WalletEntryType;
import me.nageshk.omnichannel.db.billing.PlanTerms;
import me.nageshk.omnichannel.db.billing.Subscription;
import me.nageshk.omnichannel.db.billing.SubscriptionRepository;
import me.nageshk.omnichannel.db.billing.Wallet;
import me.nageshk.omnichannel.db.billing.WalletEntry;
import me.nageshk.omnichannel.db.billing.WalletRepository;
import me.nageshk.omnichannel.security.entitlement.Entitlements;
import org.bson.Document;
import org.bson.types.Decimal128;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;

class WalletDocumentsIT extends MoneyApiTestBase {

    @Autowired WalletRepository wallets;
    @Autowired SubscriptionRepository subscriptions;
    @Autowired Entitlements entitlements;
    @Autowired MongoTemplate mongo;

    @Test
    void aWalletKeepsFourDecimalsAndItsPendingEntries() {
        Wallet w = new Wallet();
        w.setCustomerId(acme.getId());
        w.setBalance(new BigDecimal("999.2500"));
        WalletEntry e = new WalletEntry();
        e.setId("e1");
        e.setCustomerId(acme.getId());
        e.setType(WalletEntryType.USAGE);
        e.setAmount(new BigDecimal("-0.2500"));
        e.setBalanceAfter(new BigDecimal("999.2500"));
        e.setMeter(UsageMeter.WHATSAPP_MESSAGE);
        e.setSource(UsageSource.CAMPAIGN);
        e.setAt(clock.instant());
        w.getPending().add(e);
        wallets.save(w);

        Wallet read = wallets.findByCustomerId(acme.getId()).orElseThrow();
        assertThat(read.getBalance().toPlainString()).isEqualTo("999.2500");
        assertThat(read.getPending()).hasSize(1);
        assertThat(read.getPending().get(0).getAmount().toPlainString()).isEqualTo("-0.2500");
        assertThat(read.getPending().get(0).getSource()).isEqualTo(UsageSource.CAMPAIGN);
        Document raw = mongo.getCollection("wallets").find(new Document("customerId", acme.getId())).first();
        assertThat(raw.get("balance")).isInstanceOf(Decimal128.class);
        assertThat(raw.getList("pending", Document.class).get(0).get("amount")).isInstanceOf(Decimal128.class);

        Wallet twin = new Wallet();
        twin.setCustomerId(acme.getId());
        assertThatThrownBy(() -> wallets.save(twin)).isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void theMessageRateComesFromTheTermAsItWasSold() {
        assertThat(entitlements.of(acme.getId()).rate(UsageMeter.WHATSAPP_MESSAGE)).isNull();
        Subscription s = billing.assign(acme.getId(), Set.of("crm"), Map.of(LimitKey.USERS, 5));
        assertThat(entitlements.of(acme.getId()).rate(UsageMeter.WHATSAPP_MESSAGE)).isNull();

        s.getCurrent().setTerms(new PlanTerms("Gold", BillingPeriod.MONTHLY, 2, null, java.util.List.of(), java.util.List.of(),
                java.util.List.of(), Map.of(), Map.of(UsageMeter.WHATSAPP_MESSAGE, new BigDecimal("0.25"))));
        subscriptions.save(s);
        entitlements.evict(acme.getId());
        assertThat(entitlements.of(acme.getId()).rate(UsageMeter.WHATSAPP_MESSAGE)).isEqualByComparingTo("0.25");

        Subscription again = subscriptions.findByCustomerId(acme.getId()).orElseThrow();
        again.getCurrent().setTerms(new PlanTerms("Gold", BillingPeriod.MONTHLY, 2, null, java.util.List.of(), java.util.List.of(),
                java.util.List.of(), Map.of(), Map.of(UsageMeter.WHATSAPP_MESSAGE, BigDecimal.ZERO)));
        subscriptions.save(again);
        entitlements.evict(acme.getId());
        assertThat(entitlements.of(acme.getId()).rate(UsageMeter.WHATSAPP_MESSAGE)).isNull();
    }
}
