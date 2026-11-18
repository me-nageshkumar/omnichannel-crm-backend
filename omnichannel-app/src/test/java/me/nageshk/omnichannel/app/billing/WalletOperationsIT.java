package me.nageshk.omnichannel.app.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import me.nageshk.omnichannel.common.api.WalletEmptyException;
import me.nageshk.omnichannel.common.billing.UsageMeter;
import me.nageshk.omnichannel.common.billing.UsageSource;
import me.nageshk.omnichannel.common.billing.WalletEntryType;
import me.nageshk.omnichannel.db.billing.Wallet;
import me.nageshk.omnichannel.db.billing.WalletEntry;
import me.nageshk.omnichannel.db.billing.WalletEntryRepository;
import me.nageshk.omnichannel.db.billing.WalletRepository;
import me.nageshk.omnichannel.service.billing.wallet.WalletMover;
import me.nageshk.omnichannel.service.billing.wallet.WalletService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** The wallet's balance and its ledger move together, and the balance never goes below zero. */
class WalletOperationsIT extends MoneyApiTestBase {

    @Autowired WalletService service;
    @Autowired WalletRepository wallets;
    @Autowired WalletEntryRepository entries;
    @MockitoSpyBean WalletMover mover;

    private Wallet wallet() {
        return wallets.findByCustomerId(acme.getId()).orElseThrow();
    }

    private static WalletEntry draft(WalletEntryType type) {
        WalletEntry e = new WalletEntry();
        e.setType(type);
        if (type == WalletEntryType.USAGE) {
            e.setMeter(UsageMeter.WHATSAPP_MESSAGE);
            e.setSource(UsageSource.CONSOLE);
        }
        e.setNote("$not a field path");
        return e;
    }

    private List<WalletEntry> ledger() {
        return entries.findByCustomerIdOrderByAtDescIdDesc(acme.getId());
    }

    @Test
    void aCreditCreatesTheWalletAndDebitsRunItDown() {
        service.credit(acme.getId(), new BigDecimal("1000"), draft(WalletEntryType.TOPUP));
        assertThat(wallet().getBalance().toPlainString()).isEqualTo("1000.0000");
        assertThat(ledger()).singleElement().satisfies(e -> {
            assertThat(e.getAmount()).isEqualByComparingTo("1000");
            assertThat(e.getBalanceAfter()).isEqualByComparingTo("1000");
            assertThat(e.getType()).isEqualTo(WalletEntryType.TOPUP);
            assertThat(e.getNote()).isEqualTo("$not a field path");
            assertThat(e.getCustomerId()).isEqualTo(acme.getId());
            assertThat(e.getAt()).isEqualTo(clock.instant());
        });

        for (int i = 0; i < 3; i++) service.debit(acme.getId(), new BigDecimal("0.25"), draft(WalletEntryType.USAGE));
        assertThat(wallet().getBalance().toPlainString()).isEqualTo("999.2500");
        assertThat(wallet().getPending()).isEmpty();
        assertThat(ledger().stream().filter(e -> e.getType() == WalletEntryType.USAGE).map(e -> e.getBalanceAfter().toPlainString()))
                .containsExactlyInAnyOrder("999.7500", "999.5000", "999.2500");
        assertThat(ledger().stream().filter(e -> e.getType() == WalletEntryType.USAGE))
                .allSatisfy(e -> assertThat(e.getAmount()).isEqualByComparingTo("-0.25"));
    }

    @Test
    void aDebitTheWalletCannotCoverChangesNothing() {
        assertThatThrownBy(() -> service.debit(acme.getId(), new BigDecimal("0.25"), draft(WalletEntryType.USAGE)))
                .isInstanceOf(WalletEmptyException.class);
        assertThat(wallets.findByCustomerId(acme.getId())).isEmpty();

        billing.wallet(acme.getId(), "0.2000");
        assertThatThrownBy(() -> service.debit(acme.getId(), new BigDecimal("0.25"), draft(WalletEntryType.USAGE)))
                .isInstanceOf(WalletEmptyException.class);
        assertThat(wallet().getBalance()).isEqualByComparingTo("0.2");
        assertThat(ledger()).isEmpty();
        // exactly what is there can be taken
        service.debit(acme.getId(), new BigDecimal("0.2"), draft(WalletEntryType.USAGE));
        assertThat(wallet().getBalance()).isEqualByComparingTo("0");
    }

    @Test
    void anOrdersCreditOrDebitAppliesOnceEvenAfterItsEntryHasMoved() {
        assertThat(service.creditOnce("order-1", acme.getId(), new BigDecimal("1000"), draft(WalletEntryType.TOPUP))).isTrue();
        assertThat(wallet().getPending()).isEmpty();
        assertThat(service.creditOnce("order-1", acme.getId(), new BigDecimal("1000"), draft(WalletEntryType.TOPUP))).isFalse();
        assertThat(wallet().getBalance()).isEqualByComparingTo("1000");

        assertThat(service.debitOnce("order-2", acme.getId(), new BigDecimal("400"), draft(WalletEntryType.REFUND))).isTrue();
        assertThat(service.debitOnce("order-2", acme.getId(), new BigDecimal("400"), draft(WalletEntryType.REFUND))).isFalse();
        assertThat(wallet().getBalance()).isEqualByComparingTo("600");
        assertThatThrownBy(() -> service.debitOnce("order-3", acme.getId(), new BigDecimal("601"), draft(WalletEntryType.REFUND)))
                .isInstanceOf(WalletEmptyException.class);
        assertThat(ledger()).hasSize(2);
    }

    @Test
    void anInterruptedMoveLeavesTheLedgerWholeAndMovingAgainWritesOneRow() {
        doThrow(new IllegalStateException("stopped")).when(mover).move(any());
        service.credit(acme.getId(), new BigDecimal("1000"), draft(WalletEntryType.TOPUP));
        service.debit(acme.getId(), new BigDecimal("0.25"), draft(WalletEntryType.USAGE));
        // the balance already tells the truth, and both entries wait inside the wallet
        assertThat(wallet().getBalance()).isEqualByComparingTo("999.75");
        assertThat(wallet().getPending()).hasSize(2);
        assertThat(ledger()).isEmpty();
        BigDecimal sum = wallet().getPending().stream().map(WalletEntry::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sum).isEqualByComparingTo(wallet().getBalance());

        doCallRealMethod().when(mover).move(any());
        mover.move(acme.getId());
        mover.move(acme.getId());
        assertThat(wallet().getPending()).isEmpty();
        assertThat(ledger()).hasSize(2);
    }

    @Test
    void manyDebitsAtOnceNeverTakeMoreThanIsThere() throws Exception {
        billing.wallet(acme.getId(), "5.0000");
        ExecutorService pool = Executors.newFixedThreadPool(40);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            results.add(pool.submit(() -> {
                go.await();
                try {
                    service.debit(acme.getId(), new BigDecimal("0.25"), draft(WalletEntryType.USAGE));
                    return true;
                } catch (WalletEmptyException e) {
                    return false;
                }
            }));
        }
        go.countDown();
        int taken = 0;
        for (Future<Boolean> f : results) if (f.get(60, TimeUnit.SECONDS)) taken++;
        pool.shutdown();
        assertThat(taken).isEqualTo(20);
        assertThat(wallet().getBalance()).isEqualByComparingTo("0");
        mover.move(acme.getId());
        assertThat(ledger()).hasSize(20);
    }

    @Test
    void anOrdersFirstCreditIsNotLostWhenTheWalletIsCreatedAtTheSameMoment() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<String> customers = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            String customerId = new org.bson.types.ObjectId().toHexString();
            customers.add(customerId);
            CountDownLatch go = new CountDownLatch(1);
            Future<?> threshold = pool.submit(() -> {
                go.await();
                service.setThreshold(customerId, new BigDecimal("50"), "u1");
                return null;
            });
            Future<Boolean> credit = pool.submit(() -> {
                go.await();
                return service.creditOnce("order-" + customerId, customerId, new BigDecimal("100"), draft(WalletEntryType.TOPUP));
            });
            go.countDown();
            threshold.get(30, TimeUnit.SECONDS);
            credit.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();
        for (String customerId : customers) {
            assertThat(wallets.findByCustomerId(customerId).orElseThrow().getBalance()).isEqualByComparingTo("100");
        }
    }
}
