package me.nageshk.omnichannel.app.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** What the subscription view says about money: the credit held, a renewal that waits, a cancelled plan. */
class SubscriptionViewIT extends MoneyApiTestBase {

    private String path() {
        return "/api/billing/customers/" + acme.getId() + "/subscription";
    }

    @Test
    void aCustomerWithoutAPlanHoldsNothing() throws Exception {
        JsonNode v = json(GET(path(), amToken).andExpect(status().isOk()));
        assertThat(v.get("creditBalance").decimalValue()).isEqualByComparingTo("0");
        assertThat(v.get("next").isNull()).isTrue();
        assertThat(v.get("cancelled").asBoolean()).isFalse();
    }

    @Test
    void aRenewalPaidEarlyIsShownUntilItBegins() throws Exception {
        buy(acme, request("NEW", "gold-monthly"));
        on(day(2026, 12, 20));
        buy(acme, request("RENEWAL", null));

        JsonNode v = json(GET(path(), amToken).andExpect(status().isOk()));
        assertThat(v.get("termStart").asString()).isEqualTo("2026-11-30T18:30:00Z");
        assertThat(v.at("/next/planName").asString()).isEqualTo("Gold");
        assertThat(v.at("/next/termStart").asString()).isEqualTo("2026-12-31T18:30:00Z");
        assertThat(v.at("/next/termEnd").asString()).isEqualTo("2027-01-31T18:30:00Z");
        GET("/api/my/billing/subscription", adminToken).andExpect(jsonPath("$.next.planName").value("Gold"));

        // once it has begun it is simply the term, whether or not the ticker has moved it
        on(day(2027, 1, 2));
        JsonNode later = json(GET(path(), amToken));
        assertThat(later.get("next").isNull()).isTrue();
        assertThat(later.get("termStart").asString()).isEqualTo("2026-12-31T18:30:00Z");
    }

    @Test
    void accountCreditAndACancelledPlanAreShown() throws Exception {
        buy(acme, request("NEW", "gold-monthly"));
        POST("/api/billing/customers/" + acme.getId() + "/adjustments", superToken,
                Map.of("creditAmount", 250, "reason", "Goodwill")).andExpect(status().isOk());
        GET(path(), amToken).andExpect(jsonPath("$.creditBalance").value(250.00)).andExpect(jsonPath("$.cancelled").value(false));
        GET("/api/my/billing/subscription", adminToken).andExpect(jsonPath("$.creditBalance").value(250.00));

        on(day(2026, 12, 16));
        POST("/api/billing/customers/" + acme.getId() + "/cancellations", superToken, Map.of("reason", "Closing"))
                .andExpect(status().isOk());
        GET(path(), amToken).andExpect(jsonPath("$.cancelled").value(true)).andExpect(jsonPath("$.state").value("EXPIRED"))
                .andExpect(jsonPath("$.readOnly").value(true)).andExpect(jsonPath("$.graceEnd").value("2026-12-15T18:30:00Z"));
    }
}
