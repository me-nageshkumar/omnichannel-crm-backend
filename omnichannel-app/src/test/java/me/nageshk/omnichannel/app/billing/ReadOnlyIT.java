package me.nageshk.omnichannel.app.billing;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** After the grace period a customer can still read, search and export, and manage its own login; nothing else. */
class ReadOnlyIT extends BillingApiTestBase {

    private static final Set<String> EVERYTHING = Set.of("campaigns", "automations", "chatbots", "ai", "crm");

    private void termEnded(int daysAgo) {
        Instant now = Instant.now();
        billing.assign(customer.getId(), EVERYTHING, Map.of(), now.minus(60, ChronoUnit.DAYS),
                now.minus(daysAgo, ChronoUnit.DAYS));
    }

    private String ws(String rest) {
        return "/api/workspaces/" + ws.getId() + rest;
    }

    @Test
    void anExpiredCustomerReadsButDoesNotChange() throws Exception {
        termEnded(30);

        GET("/api/users/me", adminToken).andExpect(status().isOk())
                .andExpect(jsonPath("$.subscription.state").value("EXPIRED"))
                .andExpect(jsonPath("$.subscription.readOnly").value(true));
        GET(ws("/users"), adminToken).andExpect(status().isOk());
        GET(ws("/teams"), adminToken).andExpect(status().isOk());
        GET(ws("/chatbots"), adminToken).andExpect(status().isOk());

        POST(ws("/teams"), adminToken, Map.of("name", "Night shift")).andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.code").value("SUBSCRIPTION_EXPIRED"));
        PUT("/api/workspaces/" + ws.getId(), adminToken, Map.of("name", "Renamed")).andExpect(status().isPaymentRequired());
        POST(ws("/chatbots"), adminToken, Map.of("name", "Bot")).andExpect(status().isPaymentRequired());
        POST(ws("/contacts"), adminToken, Map.of("name", "Asha", "phone", "919811100001"))
                .andExpect(status().isPaymentRequired());
        DELETE(ws("/chatbots/000000000000000000000000"), adminToken).andExpect(status().isPaymentRequired());
    }

    @Test
    void searchesExportsAndTheOwnAccountStayOpen() throws Exception {
        termEnded(30);

        POST(ws("/contacts/search"), adminToken, Map.of()).andExpect(status().isOk());
        POST(ws("/crm/deals/search"), adminToken, Map.of()).andExpect(status().isOk());
        POST(ws("/crm/deals/export"), adminToken, Map.of()).andExpect(status().is2xxSuccessful());
        PUT("/api/users/me/availability", adminToken, Map.of("status", "AWAY")).andExpect(status().isOk());
        GET("/api/my/billing/subscription", adminToken).andExpect(status().isOk())
                .andExpect(jsonPath("$.readOnly").value(true));
    }

    @Test
    void inGraceEverythingStillWorks() throws Exception {
        termEnded(2);
        GET("/api/users/me", adminToken).andExpect(jsonPath("$.subscription.state").value("GRACE"));
        POST(ws("/teams"), adminToken, Map.of("name", "Night shift")).andExpect(status().isCreated());
    }

    @Test
    void platformStaffAreNeverReadOnly() throws Exception {
        termEnded(30);
        PUT("/api/customers/" + customer.getId(), amToken, Map.of("name", "Acme Renamed")).andExpect(status().isOk());
        // and they can put the customer back on a plan
        PUT("/api/billing/settings", superToken, Map.of("zone", "Asia/Kolkata", "graceDays", 7,
                "customersWithoutPlan", "UNRESTRICTED")).andExpect(status().isOk());
    }

    @Test
    void aCustomerWithoutAPlanIsReadOnlyOnlyWhenTheSettingSaysSo() throws Exception {
        POST(ws("/teams"), adminToken, Map.of("name", "One")).andExpect(status().isCreated());
        PUT("/api/billing/settings", superToken, Map.of("zone", "Asia/Kolkata", "graceDays", 7,
                "customersWithoutPlan", "READ_ONLY")).andExpect(status().isOk());
        POST(ws("/teams"), adminToken, Map.of("name", "Two")).andExpect(status().isPaymentRequired());
        GET(ws("/teams"), adminToken).andExpect(status().isOk());
    }
}
