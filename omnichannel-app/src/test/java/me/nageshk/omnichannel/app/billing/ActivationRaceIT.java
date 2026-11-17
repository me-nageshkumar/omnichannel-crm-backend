package me.nageshk.omnichannel.app.billing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import me.nageshk.omnichannel.common.billing.OrderStatus;
import me.nageshk.omnichannel.db.billing.BillingOrderRepository;
import me.nageshk.omnichannel.db.billing.Subscription;
import me.nageshk.omnichannel.db.billing.SubscriptionRepository;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Payments recorded at the same moment: each order is applied once, and only against the state it was priced on. */
class ActivationRaceIT extends MoneyApiTestBase {

    @Autowired SubscriptionRepository subscriptions;
    @Autowired BillingOrderRepository orders;

    private List<Integer> together(List<String> orderIds) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(orderIds.size());
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (String id : orderIds) {
            results.add(pool.submit(() -> {
                go.await();
                return pay(id).andReturn().getResponse().getStatus();
            }));
        }
        go.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> f : results) statuses.add(f.get(60, TimeUnit.SECONDS));
        pool.shutdown();
        return statuses;
    }

    @Test
    void oneOrderPaidTwentyTimesIsAppliedOnce() throws Exception {
        String id = raise(acme, request("NEW", "gold-monthly")).get("id").asString();
        List<String> twenty = new ArrayList<>();
        for (int i = 0; i < 20; i++) twenty.add(id);

        List<Integer> statuses = together(twenty);
        assertThat(statuses.stream().filter(s -> s == 200)).hasSize(1);
        assertThat(statuses.stream().filter(s -> s == 409)).hasSize(19);
        Subscription s = subscriptions.findByCustomerId(acme.getId()).orElseThrow();
        assertThat(s.getRevision()).isEqualTo(1);
        assertThat(orders.findById(id).orElseThrow().getStatus()).isEqualTo(OrderStatus.ACTIVATED);
    }

    @RepeatedTest(4)
    void anAddOnAndAnUpgradeQuotedOnTheSameStateDoNotBothApply() throws Exception {
        buy(acme, request("NEW", "gold-monthly"));
        on(day(2026, 12, 11));
        String addOn = raise(acme, usersRequest(5)).get("id").asString();
        String upgrade = raise(acme, request("UPGRADE", "platinum-monthly")).get("id").asString();

        List<Integer> statuses = together(List.of(addOn, upgrade));
        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        Subscription s = subscriptions.findByCustomerId(acme.getId()).orElseThrow();
        assertThat(s.getRevision()).isEqualTo(2);
        if (statuses.get(1) == 200) {
            // the upgrade won: Platinum from today, and the add-on request is dead
            assertThat(s.getCurrent().getPlanCode()).isEqualTo("platinum-monthly");
            assertThat(s.getCurrent().getAddOns()).isEmpty();
            assertThat(orders.findById(addOn).orElseThrow().getStatus()).isEqualTo(OrderStatus.SUPERSEDED);
        } else {
            assertThat(s.getCurrent().getPlanCode()).isEqualTo("gold-monthly");
            assertThat(s.getCurrent().getAddOns()).hasSize(1);
            assertThat(orders.findById(upgrade).orElseThrow().getStatus()).isEqualTo(OrderStatus.SUPERSEDED);
        }
        assertThat(orders.findAll()).noneMatch(o -> o.getStatus() == OrderStatus.ACTIVATING);
    }
}
