package me.nageshk.omnichannel.app.billing;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import me.nageshk.omnichannel.db.user.WorkspaceRole;
import me.nageshk.omnichannel.security.access.BuiltInRoles;
import org.junit.jupiter.api.Test;

/** A customer whose plan has run out can do what gets it back: ask to renew, and keep its billing details right. */
class MyBillingReadOnlyIT extends MoneyApiTestBase {

    static final String MY = "/api/my/billing";

    @Test
    void anExpiredCustomerCanRenewAndEditItsProfileAndNothingElse() throws Exception {
        buy(acme, request("NEW", "gold-monthly"));
        on(day(2027, 1, 20));
        GET("/api/users/me", adminToken).andExpect(jsonPath("$.subscription.readOnly").value(true));

        GET(MY + "/profile", adminToken).andExpect(status().isOk())
                .andExpect(jsonPath("$.legalName").value("Acme Retail Pvt Ltd")).andExpect(jsonPath("$.complete").value(true));
        PUT(MY + "/profile", adminToken, profile("Acme Retail Private Limited", "1 MG Road, Bengaluru 560001", "29"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.legalName").value("Acme Retail Private Limited"));
        PUT(MY + "/profile", adminToken, profile("", "x", "99")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.legalName").exists());

        String renewal = json(POST(MY + "/orders", adminToken, request("RENEWAL", null)).andExpect(status().isCreated()))
                .get("id").asString();
        // the request was priced for the customer as it is; its details stay put while it waits
        PUT(MY + "/profile", adminToken, profile("Acme", "1 MG Road", "27")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_PENDING"));
        // staff may fix a name, but not move the customer to another state under a priced request
        PUT(profilePath(acme), amToken, profile("Acme Retail Pvt Ltd", "1 MG Road, Bengaluru 560001", "29")).andExpect(status().isOk());
        PUT(profilePath(acme), amToken, profile("Acme Retail Pvt Ltd", "9 FC Road, Pune 411004", "27"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ORDER_PENDING"));

        POST(MY + "/orders/" + renewal + "/cancel", adminToken, null).andExpect(status().isOk());
        PUT(MY + "/profile", adminToken, profile("Acme", "9 FC Road, Pune 411004", "27")).andExpect(status().isOk());

        POST("/api/workspaces/" + ws.getId() + "/teams", adminToken, Map.of("name", "Night")).andExpect(status().isPaymentRequired());
    }

    @Test
    void theProfileIsForThoseWhoSeeBilling() throws Exception {
        String manager = testUsers.accessToken(testUsers.createInCustomer(acme.getId(), "money-manager", null,
                List.of(new WorkspaceRole(ws.getId(), testUsers.roleId(BuiltInRoles.WORKSPACE_MANAGER)))));
        GET(MY + "/profile", manager).andExpect(status().isForbidden());
        PUT(MY + "/profile", manager, profile("x", "y", "29")).andExpect(status().isForbidden());
        GET(MY + "/profile", amToken).andExpect(status().isForbidden());
    }

    @Test
    void aPlanCanNoLongerBeAssignedByHand() throws Exception {
        String path = "/api/billing/customers/" + acme.getId() + "/subscription";
        int put = PUT(path, amToken, Map.of("planCode", "gold-monthly", "startDate", "2026-12-01", "endDate", "2027-01-01"))
                .andReturn().getResponse().getStatus();
        int delete = DELETE(path, amToken).andReturn().getResponse().getStatus();
        org.assertj.core.api.Assertions.assertThat(put).isIn(404, 405);
        org.assertj.core.api.Assertions.assertThat(delete).isIn(404, 405);
        GET(path, amToken).andExpect(jsonPath("$.state").value("NONE"));
    }
}
