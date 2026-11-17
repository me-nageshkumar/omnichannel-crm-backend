package me.nageshk.omnichannel.app.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import me.nageshk.omnichannel.security.access.BuiltInRoles;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class SubscriptionApiIT extends BillingApiTestBase {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");

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

    private Map<String, Object> assign(String planCode, LocalDate start, LocalDate end, Long revision,
            Map<String, Object>... addOns) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("planCode", planCode);
        b.put("startDate", start == null ? null : start.toString());
        b.put("endDate", end == null ? null : end.toString());
        b.put("addOns", List.of(addOns));
        b.put("revision", revision);
        return b;
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
    void assigningAPlanStartsATermAtMidnightInTheBillingZone() throws Exception {
        LocalDate start = today.minusDays(1);
        LocalDate end = today.plusDays(30);
        JsonNode v = json(PUT(path, amToken, assign("gold-monthly", start, end, null,
                Map.of("kind", "GROUP", "groupCode", "automations"),
                Map.of("kind", "LIMIT", "limitKey", "USERS", "quantity", 5))).andExpect(status().isOk()));

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
    void refusesAnAssignmentThatCannotStand() throws Exception {
        LocalDate start = today;
        LocalDate end = today.plusMonths(1);
        PUT(path, amToken, assign("draft-plan", start, end, null)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.planCode").exists());
        PUT(path, amToken, assign("nope", start, end, null)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.planCode").exists());
        PUT(path, amToken, assign("gold-monthly", start, start, null)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.endDate").exists());
        PUT(path, amToken, assign("gold-monthly", null, end, null)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.startDate").exists());
        PUT(path, amToken, assign("gold-monthly", start, end, null, Map.of("kind", "GROUP", "groupCode", "crm")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.addOns").exists());   // in the plan
        PUT(path, amToken, assign("gold-monthly", start, end, null, Map.of("kind", "GROUP", "groupCode", "ai")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.addOns").exists());   // needs chatbots
        PUT(path, amToken, assign("gold-monthly", start, end, null, Map.of("kind", "GROUP", "groupCode", "inbox")))
                .andExpect(status().isBadRequest());                                                   // a default group
        PUT(path, amToken, assign("gold-monthly", start, end, null,
                Map.of("kind", "LIMIT", "limitKey", "BRANDS", "quantity", 1)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.addOns").exists());   // no limit set
        PUT(path, amToken, assign("gold-monthly", start, end, null,
                Map.of("kind", "LIMIT", "limitKey", "USERS", "quantity", 0))).andExpect(status().isBadRequest());
        PUT("/api/billing/customers/000000000000000000000000/subscription", amToken,
                assign("gold-monthly", start, end, null)).andExpect(status().isNotFound());
        GET(path, amToken).andExpect(jsonPath("$.state").value("NONE"));
    }

    @Test
    void aChangeNeedsTheRevisionItWasMadeFromAndIsKeptInTheHistory() throws Exception {
        PUT(path, amToken, assign("gold-monthly", today, today.plusMonths(1), null)).andExpect(status().isOk());
        PUT(path, amToken, assign("gold-monthly", today, today.plusMonths(2), null)).andExpect(status().isConflict());
        PUT(path, amToken, assign("gold-monthly", today, today.plusMonths(2), 5L)).andExpect(status().isConflict());
        JsonNode changed = json(PUT(path, amToken, assign("gold-monthly", today, today.plusMonths(2), 1L))
                .andExpect(status().isOk()));
        assertThat(changed.get("revision").asLong()).isEqualTo(2);

        JsonNode events = json(GET(path + "/events", amToken).andExpect(status().isOk()));
        assertThat(events).hasSize(2);
        assertThat(events.get(0).get("kind").asString()).isEqualTo("CHANGED");
        assertThat(events.get(1).get("kind").asString()).isEqualTo("ASSIGNED");
        assertThat(events.get(0).get("byName").asString()).isNotBlank();
        assertThat(events.get(0).get("summary").asString()).contains("Gold");
    }

    @Test
    void theDatesGiveTheState() throws Exception {
        PUT(path, amToken, assign("gold-monthly", today.minusDays(40), today.minusDays(1), null)).andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("GRACE")).andExpect(jsonPath("$.readOnly").value(false));
        PUT(path, amToken, assign("gold-monthly", today.minusDays(40), today.minusDays(8), 1L)).andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("EXPIRED")).andExpect(jsonPath("$.readOnly").value(true));
    }

    @Test
    void removingThePlanSetsTheCustomerFree() throws Exception {
        PUT(path, amToken, assign("gold-monthly", today, today.plusMonths(1), null)).andExpect(status().isOk());
        DELETE(path, amToken).andExpect(status().isNoContent());
        GET(path, amToken).andExpect(jsonPath("$.state").value("NONE"));
        assertThat(strings(json(GET("/api/users/me", adminToken)).at("/workspaces/0/authorities"))).contains("campaign:manage");
        assertThat(json(GET(path + "/events", amToken))).hasSize(2);
    }

    @Test
    void whoMaySeeAndChangeIt() throws Exception {
        PUT(path, amToken, assign("gold-monthly", today, today.plusMonths(1), null)).andExpect(status().isOk());
        GET(path, adminToken).andExpect(status().isForbidden());
        PUT(path, adminToken, assign("gold-monthly", today, today.plusMonths(1), 1L)).andExpect(status().isForbidden());
        DELETE(path, adminToken).andExpect(status().isForbidden());

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
        PUT(path, amToken, assign("gold-monthly", today, today.plusMonths(1), null)).andExpect(status().isOk());

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
