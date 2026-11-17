package me.nageshk.omnichannel.app.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.nageshk.omnichannel.common.billing.LimitKey;
import me.nageshk.omnichannel.db.billing.PlanRepository;
import me.nageshk.omnichannel.db.billing.PlanTerms;
import me.nageshk.omnichannel.db.billing.Subscription;
import me.nageshk.omnichannel.db.billing.SubscriptionRepository;
import me.nageshk.omnichannel.db.user.WorkspaceRole;
import me.nageshk.omnichannel.security.access.BuiltInRoles;
import me.nageshk.omnichannel.security.entitlement.Entitlements;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/** What a customer may ask for itself. Acme is on Gold from 30 Nov for thirty days. */
class MyOrdersApiIT extends MoneyApiTestBase {

    static final String MY = "/api/my/billing";

    @Autowired SubscriptionRepository subscriptions;
    @Autowired PlanRepository plans;
    @Autowired Entitlements entitlements;

    @BeforeEach
    void onGold() {
        Subscription s = billing.assign(acme.getId(), Set.of("crm"), Map.of(LimitKey.USERS, 5));
        s.getCurrent().setPlanCode("gold-monthly");
        s.getCurrent().setTerms(PlanTerms.of(plans.findById("gold-monthly").orElseThrow()));
        subscriptions.save(s);
        entitlements.evict(acme.getId());
    }

    private static List<String> codes(JsonNode array, String field) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.get(field).asString()));
        return out;
    }

    @Test
    void theOptionsAreWhatItMayAskFor() throws Exception {
        JsonNode o = json(GET(MY + "/options", adminToken).andExpect(status().isOk()));
        assertThat(codes(o.get("upgrades"), "code")).containsExactly("platinum-monthly");
        assertThat(o.at("/upgrades/0/discountedPrice").decimalValue()).isEqualByComparingTo("1500.00");
        assertThat(o.get("addOns")).hasSize(2);
        assertThat(o.at("/addOns/0/groupCode").asString()).isEqualTo("automations");
        assertThat(o.at("/addOns/0/name").asString()).isEqualTo("Automations");
        assertThat(o.at("/addOns/0/unitPrice").decimalValue()).isEqualByComparingTo("300");
        assertThat(o.at("/addOns/1/limitKey").asString()).isEqualTo("USERS");
        assertThat(o.at("/addOns/1/unit").asInt()).isEqualTo(5);
        assertThat(o.at("/renewal/allowed").asBoolean()).isTrue();
        assertThat(o.get("paymentInstructions").asString()).isEqualTo("UPI omni@bank");
    }

    @Test
    void itRaisesAnUpgradeAndAnAddOnAndSeesOnlyItsOwn() throws Exception {
        POST(MY + "/quotes", adminToken, request("UPGRADE", "platinum-monthly")).andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1770.00));
        JsonNode upgrade = json(POST(MY + "/orders", adminToken, request("UPGRADE", "platinum-monthly"))
                .andExpect(status().isCreated()));
        assertThat(upgrade.get("raisedByStaff").asBoolean()).isFalse();
        POST(MY + "/orders", adminToken, addOnRequest("automations")).andExpect(status().isCreated());
        GET(MY + "/orders", adminToken).andExpect(jsonPath("$.length()").value(2));

        raise(bolt, request("NEW", "gold-monthly"));
        GET(MY + "/orders", adminToken).andExpect(jsonPath("$.length()").value(2));
        GET(MY + "/orders", boltToken).andExpect(jsonPath("$.length()").value(1));
        // the staff queue sees them all
        GET("/api/billing/orders?status=REQUESTED", amToken).andExpect(jsonPath("$.total").value(3));
    }

    @Test
    void itCannotAskForWhatOnlyStaffGive() throws Exception {
        POST(MY + "/orders", adminToken, request("NEW", "gold-monthly")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.kind").exists());
        POST(MY + "/orders", adminToken, request("CHANGE", "platinum-monthly")).andExpect(status().isBadRequest());
        Map<String, Object> discounted = request("UPGRADE", "platinum-monthly");
        discounted.put("extraDiscountPercent", 20);
        discounted.put("extraDiscountReason", "please");
        POST(MY + "/orders", adminToken, discounted).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.extraDiscountPercent").exists());
        // a plan below its own
        POST("/api/billing/plans", superToken, lower()).andExpect(status().isCreated());
        POST("/api/billing/plans/silver-monthly/activate", superToken, null).andExpect(status().isOk());
        POST(MY + "/orders", adminToken, request("UPGRADE", "silver-monthly")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.planCode").value("Only a higher plan can be requested"));
    }

    private static Map<String, Object> lower() {
        Map<String, Object> p = gold();
        p.put("code", "silver-monthly");
        p.put("name", "Silver");
        p.put("rank", 1);
        return p;
    }

    @Test
    void itCancelsItsOwnRequestButNotOneStaffRaised() throws Exception {
        String mine = json(POST(MY + "/orders", adminToken, addOnRequest("automations"))).get("id").asString();
        POST(MY + "/orders/" + mine + "/cancel", adminToken, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        String staff = raise(acme, usersRequest(5)).get("id").asString();
        POST(MY + "/orders/" + staff + "/cancel", adminToken, null).andExpect(status().isForbidden());
        // another customer's order does not exist for it
        String theirs = raise(bolt, request("NEW", "gold-monthly")).get("id").asString();
        POST(MY + "/orders/" + theirs + "/cancel", adminToken, null).andExpect(status().isNotFound());
    }

    @Test
    void onlyThoseWhoMayRequestCanRaise() throws Exception {
        String manager = testUsers.accessToken(testUsers.createInCustomer(acme.getId(), "money-manager", null,
                List.of(new WorkspaceRole(ws.getId(), testUsers.roleId(BuiltInRoles.WORKSPACE_MANAGER)))));
        GET(MY + "/options", manager).andExpect(status().isForbidden());
        POST(MY + "/orders", manager, addOnRequest("automations")).andExpect(status().isForbidden());
        GET(MY + "/orders", amToken).andExpect(status().isForbidden());
    }

    @Test
    void aReadOnlyCustomerCanStillAskToRenew() throws Exception {
        on(day(2027, 1, 20));
        GET("/api/users/me", adminToken).andExpect(jsonPath("$.subscription.readOnly").value(true));
        JsonNode renewal = json(POST(MY + "/orders", adminToken, request("RENEWAL", null)).andExpect(status().isCreated()));
        assertThat(renewal.get("kind").asString()).isEqualTo("RENEWAL");
        POST(MY + "/orders/" + renewal.get("id").asString() + "/cancel", adminToken, null).andExpect(status().isOk());
        // and nothing else
        POST("/api/workspaces/" + ws.getId() + "/teams", adminToken, Map.of("name", "Night")).andExpect(status().isPaymentRequired());
    }
}
