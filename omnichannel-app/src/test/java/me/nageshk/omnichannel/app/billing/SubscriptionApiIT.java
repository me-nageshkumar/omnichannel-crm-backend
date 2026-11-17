package me.nageshk.omnichannel.app.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.nageshk.omnichannel.common.billing.LimitKey;
import me.nageshk.omnichannel.db.billing.AddOnKind;
import me.nageshk.omnichannel.db.billing.PlanRepository;
import me.nageshk.omnichannel.db.billing.PlanTerms;
import me.nageshk.omnichannel.db.billing.Subscription;
import me.nageshk.omnichannel.db.billing.SubscriptionAddOn;
import me.nageshk.omnichannel.db.billing.SubscriptionRepository;
import me.nageshk.omnichannel.security.access.BuiltInRoles;
import me.nageshk.omnichannel.security.entitlement.Entitlements;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

class SubscriptionApiIT extends BillingApiTestBase {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @Autowired SubscriptionRepository subscriptions;
    @Autowired PlanRepository plans;
    @Autowired Entitlements entitlements;

    String path;
    LocalDate today;

    @BeforeEach
    void plans() throws Exception {
        path = "/api/billing/customers/" + customer.getId() + "/subscription";
        today = LocalDate.now(IST);
        POST("/api/billing/plans", superToken, planBody("gold-monthly")).andExpect(status().isCreated());
        POST("/api/billing/plans/gold-monthly/activate", superToken, null).andExpect(status().isOk());
        POST("/api/billing/plans", superToken, planBody("draft-plan")).andExpect(status().isCreated());
    }

    /** Puts the customer on Gold for these dates, as an activated order would. */
    private Subscription give(LocalDate start, LocalDate end) {
        Subscription s = billing.assign(customer.getId(), Set.of("crm"), Map.of(LimitKey.USERS, 5),
                start.atStartOfDay(IST).toInstant(), end.atStartOfDay(IST).toInstant());
        s.getCurrent().setPlanCode("gold-monthly");
        s.getCurrent().setTerms(PlanTerms.of(plans.findById("gold-monthly").orElseThrow()));
        Subscription saved = subscriptions.save(s);
        entitlements.evict(customer.getId());
        return saved;
    }

    private static SubscriptionAddOn addOn(AddOnKind kind, String groupCode, LimitKey key, int quantity) {
        SubscriptionAddOn a = new SubscriptionAddOn();
        a.setId(java.util.UUID.randomUUID().toString());
        a.setKind(kind);
        a.setGroupCode(groupCode);
        a.setLimitKey(key);
        a.setQuantity(quantity);
        return a;
    }

    private static JsonNode limit(JsonNode view, String key) {
        for (JsonNode l : view.get("limits")) if (l.get("key").asString().equals(key)) return l;
        throw new AssertionError("no limit " + key);
    }

    private static List<String> groupsFrom(JsonNode view, String source) {
        List<String> out = new ArrayList<>();
        for (JsonNode g : view.get("groups")) if (g.get("source").asString().equals(source)) out.add(g.get("code").asString());
        return out;
    }

    @Test
    void aCustomerWithoutAPlanHasNoneAndNoLimits() throws Exception {
        JsonNode v = json(GET(path, amToken).andExpect(status().isOk()));
        assertThat(v.get("state").asString()).isEqualTo("NONE");
        assertThat(v.get("readOnly").asBoolean()).isFalse();
        assertThat(v.get("plan").isNull()).isTrue();
        assertThat(v.get("termEnd").isNull()).isTrue();
        assertThat(v.get("revision").asLong()).isZero();
        assertThat(v.get("limits")).hasSize(10);
        assertThat(limit(v, "USERS").get("limit").isNull()).isTrue();
        assertThat(limit(v, "USERS").get("used").asInt()).isEqualTo(1);
        assertThat(limit(v, "CHATBOT_NODES").get("used").isNull()).isTrue();
    }

    @Test
    void aPlanWithAddOnsIsShownWithItsTermAndLimits() throws Exception {
        LocalDate start = today.minusDays(1);
        LocalDate end = today.plusDays(30);
        Subscription given = give(start, end);
        given.getCurrent().getAddOns().add(addOn(AddOnKind.GROUP, "automations", null, 0));
        given.getCurrent().getAddOns().add(addOn(AddOnKind.LIMIT, null, LimitKey.USERS, 5));
        subscriptions.save(given);
        entitlements.evict(customer.getId());
        JsonNode v = json(GET(path, amToken).andExpect(status().isOk()));

        assertThat(v.get("state").asString()).isEqualTo("ACTIVE");
        assertThat(v.at("/plan/code").asString()).isEqualTo("gold-monthly");
        assertThat(v.at("/plan/name").asString()).isEqualTo("Gold");
        assertThat(v.at("/plan/period").asString()).isEqualTo("MONTHLY");
        assertThat(Instant.parse(v.get("termStart").asString())).isEqualTo(start.atStartOfDay(IST).toInstant());
        assertThat(Instant.parse(v.get("termEnd").asString())).isEqualTo(end.atStartOfDay(IST).toInstant());
        assertThat(Instant.parse(v.get("graceEnd").asString())).isEqualTo(end.plusDays(7).atStartOfDay(IST).toInstant());
        assertThat(v.get("zone").asString()).isEqualTo("Asia/Kolkata");
        assertThat(groupsFrom(v, "DEFAULT")).containsExactly("core", "inbox", "contacts", "templates");
        assertThat(groupsFrom(v, "PLAN")).containsExactly("crm");
        assertThat(groupsFrom(v, "ADDON")).containsExactly("automations");
        assertThat(v.get("addOns")).hasSize(2);
        assertThat(limit(v, "USERS").get("limit").asInt()).isEqualTo(10);
        assertThat(limit(v, "USERS").get("used").asInt()).isEqualTo(1);
        assertThat(limit(v, "USERS").get("label").asString()).isEqualTo("Users");
        assertThat(limit(v, "BRANDS").get("limit").isNull()).isTrue();
        assertThat(v.get("revision").asLong()).isEqualTo(1);

        // the customer's users feel it at once
        JsonNode me = json(GET("/api/users/me", adminToken));
        assertThat(strings(me.at("/workspaces/0/authorities"))).contains("crm:view", "automation:manage")
                .doesNotContain("campaign:manage");
        assertThat(me.at("/subscription/planName").asString()).isEqualTo("Gold");
    }

    @Test
    void theDatesGiveTheState() throws Exception {
        give(today.minusDays(40), today.minusDays(1));
        GET(path, amToken).andExpect(jsonPath("$.state").value("GRACE")).andExpect(jsonPath("$.readOnly").value(false));
        give(today.minusDays(40), today.minusDays(8));
        GET(path, amToken).andExpect(jsonPath("$.state").value("EXPIRED")).andExpect(jsonPath("$.readOnly").value(true));
    }

    @Test
    void whoMaySeeIt() throws Exception {
        give(today, today.plusMonths(1));
        GET(path, adminToken).andExpect(status().isForbidden());
        GET(path + "/events", adminToken).andExpect(status().isForbidden());

        GET("/api/my/billing/subscription", adminToken).andExpect(status().isOk())
                .andExpect(jsonPath("$.plan.code").value("gold-monthly"));
        String managerToken = testUsers.accessToken(testUsers.createInCustomer(customer.getId(), "bill-manager", null,
                List.of(new me.nageshk.omnichannel.db.user.WorkspaceRole(ws.getId(),
                        testUsers.roleId(BuiltInRoles.WORKSPACE_MANAGER)))));
        GET("/api/my/billing/subscription", managerToken).andExpect(status().isForbidden());
        GET("/api/my/billing/subscription", amToken).andExpect(status().isForbidden());

        JsonNode keys = json(GET("/api/billing/limit-keys", amToken).andExpect(status().isOk()));
        assertThat(keys).hasSize(10);
        assertThat(keys.get(0).get("key").asString()).isEqualTo("USERS");
        assertThat(keys.get(0).get("scope").asString()).isEqualTo("CUSTOMER");
        assertThat(keys.get(0).get("label").asString()).isEqualTo("Users");
    }

    @Test
    void theCustomerListShowsThePlanAndFiltersThoseWithout() throws Exception {
        testUsers.customer("Zed without a plan");
        give(today, today.plusMonths(1));

        JsonNode all = json(GET("/api/customers", amToken).andExpect(status().isOk()));
        assertThat(all.get("total").asInt()).isEqualTo(2);
        JsonNode acme = all.get("items").get(0);
        assertThat(acme.get("name").asString()).isEqualTo("Acme");
        assertThat(acme.get("planName").asString()).isEqualTo("Gold");
        assertThat(acme.get("subscriptionState").asString()).isEqualTo("ACTIVE");
        assertThat(all.get("items").get(1).get("planName").isNull()).isTrue();
        assertThat(all.get("items").get(1).get("subscriptionState").asString()).isEqualTo("NONE");

        JsonNode without = json(GET("/api/customers?noPlan=true", amToken).andExpect(status().isOk()));
        assertThat(without.get("total").asInt()).isEqualTo(1);
        assertThat(without.get("items").get(0).get("name").asString()).isEqualTo("Zed without a plan");

        GET("/api/customers/" + customer.getId(), amToken).andExpect(status().isOk())
                .andExpect(jsonPath("$.customer.planName").value("Gold"));
    }
}
