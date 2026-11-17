package me.nageshk.omnichannel.app.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import me.nageshk.omnichannel.db.billing.Subscription;
import me.nageshk.omnichannel.db.billing.SubscriptionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/** What staff may change by hand: the end of a term, and the account credit. Always with a reason. */
class AdjustmentsIT extends MoneyApiTestBase {

    @Autowired SubscriptionRepository subscriptions;

    private String path() {
        return "/api/billing/customers/" + acme.getId() + "/adjustments";
    }

    private Subscription sub() {
        return subscriptions.findByCustomerId(acme.getId()).orElseThrow();
    }

    private static java.util.List<String> summaries(JsonNode events) {
        java.util.List<String> out = new java.util.ArrayList<>();
        events.forEach(e -> out.add(e.get("summary").asString()));
        return out;
    }

    private static Map<String, Object> body(String termEnd, Object creditAmount, String reason) {
        Map<String, Object> b = new LinkedHashMap<>();
        if (termEnd != null) b.put("termEnd", termEnd);
        if (creditAmount != null) b.put("creditAmount", creditAmount);
        if (reason != null) b.put("reason", reason);
        return b;
    }

    @Test
    void theEndOfTheTermCanBeMoved() throws Exception {
        buy(acme, request("NEW", "gold-monthly"));
        on(day(2026, 12, 11));
        String renewal = raise(acme, request("RENEWAL", null)).get("id").asString();

        JsonNode order = json(POST(path(), amToken, body("2027-01-11", null, "Ten days for the outage")).andExpect(status().isOk()));
        assertThat(order.get("kind").asString()).isEqualTo("ADJUST");
        assertThat(order.get("status").asString()).isEqualTo("ACTIVATED");
        assertThat(order.get("reason").asString()).isEqualTo("Ten days for the outage");
        assertThat(order.get("total").decimalValue()).isEqualByComparingTo("0");
        assertThat(order.get("invoiceNumber").isNull()).isTrue();

        Subscription s = sub();
        assertThat(s.getCurrent().getTermEnd()).isEqualTo(Instant.parse("2027-01-10T18:30:00Z"));
        assertThat(s.getCurrent().getPlanCode()).isEqualTo("gold-monthly");
        assertThat(s.getCurrent().getPlanPaid()).isEqualByComparingTo("900.00");
        assertThat(s.getTermRevision()).isEqualTo(2);
        GET("/api/billing/orders/" + renewal, amToken).andExpect(jsonPath("$.status").value("SUPERSEDED"));
        GET("/api/billing/customers/" + acme.getId() + "/subscription", amToken)
                .andExpect(jsonPath("$.termEnd").value("2027-01-10T18:30:00Z"));
        JsonNode events = json(GET("/api/billing/customers/" + acme.getId() + "/subscription/events", amToken));
        assertThat(summaries(events)).anyMatch(x -> x.contains("Ten days for the outage"));
    }

    @Test
    void anEndBeforeTheStartIsRefused() throws Exception {
        buy(acme, request("NEW", "gold-monthly"));
        POST(path(), amToken, body("2026-12-01", null, "x")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.termEnd").exists());
        POST("/api/billing/customers/" + bolt.getId() + "/adjustments", amToken, body("2027-01-11", null, "x"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.termEnd").exists());
    }

    @Test
    void accountCreditIsPostedByThoseWhoMayRefund() throws Exception {
        buy(acme, request("NEW", "gold-monthly"));
        POST(path(), amToken, body(null, 250, "Goodwill")).andExpect(status().isForbidden());
        POST(path(), superToken, body(null, 250, "Goodwill for the outage")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVATED"));
        assertThat(sub().getCreditBalance()).isEqualByComparingTo("250.00");
        // a posting does not disturb the plan or the requests that wait
        assertThat(sub().getRevision()).isEqualTo(1);
        JsonNode events = json(GET("/api/billing/customers/" + acme.getId() + "/subscription/events", amToken));
        assertThat(summaries(events)).anyMatch(x -> x.contains("Goodwill for the outage") && x.contains("+250.00"));

        POST(path(), superToken, body(null, -300, "Correction")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOT_ENOUGH_CREDIT"));
        POST(path(), superToken, body(null, -100, "Correction")).andExpect(status().isOk());
        assertThat(sub().getCreditBalance()).isEqualByComparingTo("150.00");
    }

    @Test
    void anAdjustmentIsOneThingWithAReason() throws Exception {
        buy(acme, request("NEW", "gold-monthly"));
        POST(path(), superToken, body("2027-01-11", 10, "Both")).andExpect(status().isBadRequest());
        POST(path(), superToken, body(null, null, "Neither")).andExpect(status().isBadRequest());
        POST(path(), superToken, body("2027-01-11", null, " ")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.reason").exists());
        POST(path(), superToken, body(null, 0, "Zero")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.creditAmount").exists());
        POST(path(), superToken, body(null, 1.005, "Three decimals")).andExpect(status().isBadRequest());
        POST(path(), adminToken, body("2027-01-11", null, "x")).andExpect(status().isForbidden());
        assertThat(sub().getRevision()).isEqualTo(1);
    }
}
