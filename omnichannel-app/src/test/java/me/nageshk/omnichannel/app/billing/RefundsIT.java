package me.nageshk.omnichannel.app.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import me.nageshk.omnichannel.db.billing.Refund;
import me.nageshk.omnichannel.db.billing.RefundRepository;
import me.nageshk.omnichannel.db.billing.RefundStatus;
import me.nageshk.omnichannel.db.billing.Subscription;
import me.nageshk.omnichannel.db.billing.SubscriptionRepository;
import me.nageshk.omnichannel.service.billing.refund.RefundService;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/** Paying account credit back to the customer. Acme is on Gold and is owed 182.71. */
class RefundsIT extends MoneyApiTestBase {

    @Autowired SubscriptionRepository subscriptions;
    @Autowired RefundRepository refunds;
    @Autowired RefundService refundService;

    @BeforeEach
    void owed() throws Exception {
        buy(acme, request("NEW", "gold-monthly"));
        POST("/api/billing/customers/" + acme.getId() + "/adjustments", superToken,
                Map.of("creditAmount", 182.71, "reason", "Add-on cancelled by hand")).andExpect(status().isOk());
    }

    private String path() {
        return "/api/billing/customers/" + acme.getId() + "/refunds";
    }

    private BigDecimal balance() {
        return subscriptions.findByCustomerId(acme.getId()).orElseThrow().getCreditBalance();
    }

    private static Map<String, Object> refund(Object amount) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("amount", amount);
        b.put("method", "BANK_TRANSFER");
        b.put("reference", "NEFT-77");
        b.put("paidOn", "2026-12-01");
        b.put("note", "To the account on file");
        return b;
    }

    @Test
    void aRefundTakesTheAmountOffTheAccountCredit() throws Exception {
        JsonNode r = json(POST(path(), superToken, refund(100)).andExpect(status().isCreated()));
        assertThat(r.get("number").asString()).isEqualTo("RF-000001");
        assertThat(r.get("status").asString()).isEqualTo("RECORDED");
        assertThat(r.get("amount").decimalValue()).isEqualByComparingTo("100.00");
        assertThat(r.get("recordedByName").asString()).isNotBlank();
        assertThat(balance()).isEqualByComparingTo("82.71");

        GET(path(), amToken).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].number").value("RF-000001")).andExpect(jsonPath("$[0].method").value("BANK_TRANSFER"))
                .andExpect(jsonPath("$[0].reference").value("NEFT-77")).andExpect(jsonPath("$[0].paidOn").value("2026-12-01"));
        GET("/api/my/billing/refunds", adminToken).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].amount").value(100.00));
        GET("/api/my/billing/refunds", boltToken).andExpect(jsonPath("$.length()").value(0));
        JsonNode events = json(GET("/api/billing/customers/" + acme.getId() + "/subscription/events", amToken));
        List<String> summaries = new ArrayList<>();
        events.forEach(e -> summaries.add(e.get("summary").asString()));
        assertThat(summaries).anyMatch(s -> s.contains("RF-000001") && s.contains("100.00"));
    }

    @Test
    void aRefundCannotExceedTheCredit() throws Exception {
        POST(path(), superToken, refund(182.72)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOT_ENOUGH_CREDIT"));
        assertThat(balance()).isEqualByComparingTo("182.71");
        assertThat(refunds.findAll()).singleElement().matches(r -> r.getStatus() == RefundStatus.FAILED);
        // a failed attempt is not shown to the customer
        GET("/api/my/billing/refunds", adminToken).andExpect(jsonPath("$.length()").value(0));
        POST(path(), superToken, refund(182.71)).andExpect(status().isCreated());
        assertThat(balance()).isEqualByComparingTo("0");
        // a customer that never had a plan holds no credit
        POST("/api/billing/customers/" + bolt.getId() + "/refunds", superToken, refund(1)).andExpect(status().isConflict());
    }

    @Test
    void aRefundMustBeDescribedByThoseWhoMayRefund() throws Exception {
        POST(path(), amToken, refund(10)).andExpect(status().isForbidden());
        POST(path(), adminToken, refund(10)).andExpect(status().isForbidden());
        POST(path(), superToken, refund(0)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.amount").exists());
        POST(path(), superToken, refund(1.005)).andExpect(status().isBadRequest());
        Map<String, Object> b = refund(10);
        b.put("method", "NONE");
        POST(path(), superToken, b).andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.method").exists());
        b = refund(10);
        b.remove("reference");
        POST(path(), superToken, b).andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.reference").exists());
        b = refund(10);
        b.put("paidOn", "2026-12-02");
        POST(path(), superToken, b).andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.paidOn").exists());
        assertThat(refunds.count()).isZero();
        assertThat(balance()).isEqualByComparingTo("182.71");
    }

    private Refund recording(String amount) {
        Refund r = new Refund();
        r.setId(new ObjectId().toHexString());
        r.setNumber("RF-9" + r.getId().substring(18));
        r.setCustomerId(acme.getId());
        r.setAmount(new BigDecimal(amount));
        r.setMethod("UPI");
        r.setReference("x");
        r.setPaidOn(LocalDate.of(2026, 12, 1));
        r.setRecordedBy(superUser.getId());
        r.setRecordedAt(clock.instant());
        r.setStatus(RefundStatus.RECORDING);
        return refunds.save(r);
    }

    @Test
    void aRefundInterruptedAfterTheBalanceChangedIsNotTakenTwice() {
        Refund r = recording("50.00");
        Subscription s = subscriptions.findByCustomerId(acme.getId()).orElseThrow();
        s.setCreditBalance(new BigDecimal("132.71"));
        s.getRefundIds().add(r.getId());
        subscriptions.save(s);

        refundService.finishPending(acme.getId());
        assertThat(refunds.findById(r.getId()).orElseThrow().getStatus()).isEqualTo(RefundStatus.RECORDED);
        assertThat(balance()).isEqualByComparingTo("132.71");
    }

    @Test
    void aRefundInterruptedBeforeTheBalanceChangedIsTakenOnce() {
        Refund r = recording("50.00");
        refundService.finishPending(acme.getId());
        refundService.finishPending(acme.getId());
        assertThat(refunds.findById(r.getId()).orElseThrow().getStatus()).isEqualTo(RefundStatus.RECORDED);
        assertThat(balance()).isEqualByComparingTo("132.71");
    }

    @Test
    void refundsRecordedAtOnceNeverTakeMoreThanIsHeld() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            results.add(pool.submit(() -> {
                go.await();
                return POST(path(), superToken, refund(50)).andReturn().getResponse().getStatus();
            }));
        }
        go.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> f : results) statuses.add(f.get(60, TimeUnit.SECONDS));
        pool.shutdown();

        assertThat(statuses.stream().filter(s -> s == 201)).hasSize(3);
        assertThat(statuses.stream().filter(s -> s == 409)).hasSize(7);
        assertThat(balance()).isEqualByComparingTo("32.71");
        assertThat(refunds.findAll().stream().filter(r -> r.getStatus() == RefundStatus.RECORDED)).hasSize(3);
        assertThat(refunds.findAll()).noneMatch(r -> r.getStatus() == RefundStatus.RECORDING);
    }
}
