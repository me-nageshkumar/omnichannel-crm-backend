package me.nageshk.omnichannel.app.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** The signed-in user is told when the wallet their messages are charged to runs low. */
class LowWalletIT extends MoneyApiTestBase {

    private String threshold(me.nageshk.omnichannel.db.tenant.Customer c) {
        return "/api/billing/customers/" + c.getId() + "/wallet/threshold";
    }

    private JsonNode me(String token) throws Exception {
        return json(GET("/api/users/me", token).andExpect(status().isOk()));
    }

    @Test
    void aChargedCustomerSeesTheWalletLowBelowTheThresholdOnly() throws Exception {
        buy(acme, request("NEW", "gold-monthly"));
        tokens();
        // no wallet yet
        assertThat(me(adminToken).at("/subscription/lowWallet").asBoolean()).isFalse();

        billing.wallet(acme.getId(), "100.0000");
        // a wallet without a threshold warns nobody
        assertThat(me(adminToken).at("/subscription/lowWallet").isBoolean()).isTrue();
        assertThat(me(adminToken).at("/subscription/lowWallet").asBoolean()).isFalse();

        PUT(threshold(acme), amToken, Map.of("lowBalanceAt", 200)).andExpect(status().isOk());
        assertThat(me(adminToken).at("/subscription/lowWallet").asBoolean()).isTrue();

        PUT(threshold(acme), amToken, Map.of("lowBalanceAt", 100)).andExpect(status().isOk());
        assertThat(me(adminToken).at("/subscription/lowWallet").asBoolean()).isFalse();
    }

    @Test
    void aCustomerWhosePlanChargesNothingIsNeverWarned() throws Exception {
        billing.assign(bolt.getId(), Set.of(), Map.of());
        billing.wallet(bolt.getId(), "10.0000");
        PUT(threshold(bolt), amToken, Map.of("lowBalanceAt", 200)).andExpect(status().isOk());
        tokens();
        assertThat(me(boltToken).at("/subscription/planName").asString()).isEqualTo("Test plan");
        assertThat(me(boltToken).at("/subscription/lowWallet").isBoolean()).isTrue();
        assertThat(me(boltToken).at("/subscription/lowWallet").asBoolean()).isFalse();
    }

    @Test
    void platformStaffHaveNoSubscriptionAndNoWarning() throws Exception {
        JsonNode staff = me(amToken);
        assertThat(staff.get("subscription") == null || staff.get("subscription").isNull()).isTrue();
    }
}
