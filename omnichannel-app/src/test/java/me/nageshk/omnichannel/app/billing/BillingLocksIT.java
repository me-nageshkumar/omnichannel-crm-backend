package me.nageshk.omnichannel.app.billing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import me.nageshk.omnichannel.db.billing.BillingLock;
import me.nageshk.omnichannel.service.billing.order.BillingLocks;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;

class BillingLocksIT extends MoneyApiTestBase {

    @Autowired BillingLocks locks;
    @Autowired MongoTemplate mongo;

    @Test
    void changesToOneCustomerRunOneAfterAnother() throws Exception {
        AtomicInteger inside = new AtomicInteger();
        AtomicInteger most = new AtomicInteger();
        AtomicInteger ran = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(6);
        CountDownLatch go = new CountDownLatch(1);
        Future<?>[] all = new Future<?>[6];
        for (int i = 0; i < 6; i++) {
            all[i] = pool.submit(() -> {
                go.await();
                return locks.withCustomer("lock-c1", () -> {
                    most.accumulateAndGet(inside.incrementAndGet(), Math::max);
                    try {
                        Thread.sleep(40);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    inside.decrementAndGet();
                    return ran.incrementAndGet();
                });
            });
        }
        go.countDown();
        for (Future<?> f : all) f.get(20, TimeUnit.SECONDS);
        pool.shutdown();
        assertThat(most.get()).isEqualTo(1);
        assertThat(ran.get()).isEqualTo(6);
    }

    @Test
    void anotherCustomerIsNotHeldUpAndTheHolderMayCallAgain() {
        String result = locks.withCustomer("lock-c1", () -> locks.withCustomer("lock-c2",
                () -> locks.withCustomer("lock-c1", () -> "inner")));
        assertThat(result).isEqualTo("inner");
    }

    @Test
    void aLeaseLeftByADeadHolderIsTakenWhenItRunsOut() {
        BillingLock dead = new BillingLock();
        dead.setId("lock-c3");
        dead.setHolder("gone");
        dead.setLockedUntil(clock.instant().plusSeconds(30));
        mongo.save(dead);
        clock.advance(Duration.ofSeconds(31));
        assertThat(locks.withCustomer("lock-c3", () -> "ran")).isEqualTo("ran");
    }

    @Test
    void theLeaseGoesBackWhenTheWorkFails() {
        try {
            locks.withCustomer("lock-c4", () -> {
                throw new IllegalStateException("boom");
            });
        } catch (IllegalStateException expected) {
            // the lease must be free again
        }
        assertThat(locks.withCustomer("lock-c4", () -> "ran")).isEqualTo("ran");
    }
}
