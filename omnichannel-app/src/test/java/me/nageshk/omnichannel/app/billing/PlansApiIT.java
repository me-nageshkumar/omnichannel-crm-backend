package me.nageshk.omnichannel.app.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import me.nageshk.omnichannel.db.billing.Subscription;
import me.nageshk.omnichannel.db.billing.SubscriptionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

class PlansApiIT extends BillingApiTestBase {

    static final String PLANS = "/api/billing/plans";

    @Autowired SubscriptionRepository subscriptions;

    private Map<String, Object> with(String field, Object value) {
        Map<String, Object> p = planBody("p-" + field.toLowerCase());
        p.put(field, value);
        return p;
    }

    private void refused(Map<String, Object> plan, String field) throws Exception {
        POST(PLANS, superToken, plan).andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields." + field).exists());
    }

    @Test
    void aNewPlanIsADraftWithItsPricesWorkedOut() throws Exception {
        JsonNode p = json(POST(PLANS, superToken, planBody("gold-monthly")).andExpect(status().isCreated()));
        assertThat(p.get("status").asString()).isEqualTo("DRAFT");
        assertThat(p.get("actualPrice").decimalValue()).isEqualByComparingTo("1000");
        assertThat(p.get("discountAmount").decimalValue()).isEqualByComparingTo("100");
        assertThat(p.get("discountedPrice").decimalValue()).isEqualByComparingTo("900");
        assertThat(p.get("customers").asInt()).isZero();
        assertThat(p.at("/limits/USERS").asInt()).isEqualTo(5);
        assertThat(p.at("/rates/WHATSAPP_MESSAGE").decimalValue()).isEqualByComparingTo("0.05");
        assertThat(p.at("/limitAddOns/0/unit").asInt()).isEqualTo(5);

        JsonNode price = json(POST(PLANS + "/price", superToken, Map.of("items", planBody("x").get("items"),
                "discountPercent", 10)).andExpect(status().isOk()));
        assertThat(price.get("discountedPrice").decimalValue()).isEqualByComparingTo(p.get("discountedPrice").decimalValue());

        GET(PLANS, amToken).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        GET(PLANS + "/gold-monthly", amToken).andExpect(status().isOk());
        GET(PLANS + "?status=ACTIVE", amToken).andExpect(jsonPath("$.length()").value(0));
        POST(PLANS, amToken, planBody("silver")).andExpect(status().isForbidden());
        GET(PLANS, adminToken).andExpect(status().isForbidden());
    }

    @Test
    void refusesAPlanThatDoesNotAddUp() throws Exception {
        refused(with("code", "Bad Code"), "code");
        refused(with("name", ""), "name");
        refused(with("period", null), "period");
        refused(with("rank", 1000), "rank");
        refused(with("discountPercent", 100.5), "discountPercent");
        refused(with("discountPercent", 10.123), "discountPercent");
        refused(with("items", List.of(Map.of("groupCode", "inbox", "price", 10))), "items");       // a default group
        refused(with("items", List.of(Map.of("groupCode", "nope", "price", 10))), "items");
        refused(with("items", List.of(Map.of("groupCode", "crm", "price", -1))), "items");
        refused(with("items", List.of(Map.of("groupCode", "crm", "price", 1.999))), "items");
        refused(with("addOns", List.of(Map.of("groupCode", "crm", "price", 10))), "addOns");       // already included
        refused(with("items", List.of(Map.of("groupCode", "ai", "price", 10))), "items");          // needs chatbots
        refused(with("addOns", List.of(Map.of("groupCode", "ai", "price", 10))), "addOns");        // needs chatbots
        refused(with("limits", Map.of("USERS", -1)), "limits");
        refused(with("limitAddOns", List.of(Map.of("key", "USERS", "unitPrice", 10, "unit", 0))), "limitAddOns");
        refused(with("limitAddOns", List.of(Map.of("key", "USERS", "unitPrice", 10, "unit", 1),
                Map.of("key", "USERS", "unitPrice", 20, "unit", 5))), "limitAddOns");
        refused(with("rates", Map.of("WHATSAPP_MESSAGE", 0.12345)), "rates");
        POST(PLANS, superToken, planBody("gold-monthly")).andExpect(status().isCreated());
        refused(planBody("gold-monthly"), "code");

        // chatbots are started by automation rules
        refused(with("items", List.of(Map.of("groupCode", "chatbots", "price", 10))), "items");
        // AI is fine once chatbots is there, included or offered
        Map<String, Object> ok = planBody("with-ai");
        ok.put("items", List.of(Map.of("groupCode", "automations", "price", 300),
                Map.of("groupCode", "chatbots", "price", 500)));
        ok.put("addOns", List.of(Map.of("groupCode", "ai", "price", 200)));
        POST(PLANS, superToken, ok).andExpect(status().isCreated());
    }

    @Test
    void movesThroughItsStatuses() throws Exception {
        POST(PLANS, superToken, planBody("gold-monthly")).andExpect(status().isCreated());
        POST(PLANS + "/gold-monthly/retire", superToken, null).andExpect(status().isBadRequest());
        POST(PLANS + "/gold-monthly/activate", superToken, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        DELETE(PLANS + "/gold-monthly", superToken).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PLAN_IN_USE"));
        POST(PLANS + "/gold-monthly/retire", superToken, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RETIRED"));
        Map<String, Object> edit = planBody("gold-monthly");
        edit.put("version", json(GET(PLANS + "/gold-monthly", superToken)).get("version").asLong());
        PUT(PLANS + "/gold-monthly", superToken, edit).andExpect(status().isBadRequest());
        POST(PLANS + "/gold-monthly/activate", superToken, null).andExpect(status().isOk());

        POST(PLANS, superToken, planBody("draft-one")).andExpect(status().isCreated());
        DELETE(PLANS + "/draft-one", superToken).andExpect(status().isNoContent());
        GET(PLANS + "/draft-one", superToken).andExpect(status().isNotFound());
        assertThat(json(GET(PLANS + "/gold-monthly/history", superToken))).hasSizeGreaterThanOrEqualTo(4);
    }

    @Test
    void duplicatesIntoAnotherPeriod() throws Exception {
        POST(PLANS, superToken, planBody("gold-monthly")).andExpect(status().isCreated());
        JsonNode copy = json(POST(PLANS + "/gold-monthly/duplicate", superToken,
                Map.of("code", "gold-yearly", "name", "Gold yearly", "period", "YEARLY")).andExpect(status().isCreated()));
        assertThat(copy.get("status").asString()).isEqualTo("DRAFT");
        assertThat(copy.get("period").asString()).isEqualTo("YEARLY");
        assertThat(copy.get("rank").asInt()).isEqualTo(2);
        assertThat(copy.get("actualPrice").decimalValue()).isEqualByComparingTo("1000");
        assertThat(copy.at("/addOns/0/groupCode").asString()).isEqualTo("automations");
        POST(PLANS + "/gold-monthly/duplicate", superToken, Map.of("code", "gold-monthly", "name", "X", "period", "YEARLY"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void editingAnActivePlanLeavesItsCustomersAlone() throws Exception {
        POST(PLANS, superToken, planBody("gold-monthly")).andExpect(status().isCreated());
        POST(PLANS + "/gold-monthly/activate", superToken, null).andExpect(status().isOk());
        Subscription sold = billing.assign(customer.getId(), java.util.Set.of("crm"), Map.of());
        sold.getCurrent().setPlanCode("gold-monthly");
        subscriptions.save(sold);

        Map<String, Object> edit = new LinkedHashMap<>(planBody("gold-monthly"));
        edit.put("items", List.of(Map.of("groupCode", "crm", "price", 2000)));
        edit.put("version", json(GET(PLANS + "/gold-monthly", superToken)).get("version").asLong());
        JsonNode after = json(PUT(PLANS + "/gold-monthly", superToken, edit).andExpect(status().isOk()));

        assertThat(after.get("revision").asLong()).isEqualTo(1);
        assertThat(after.get("actualPrice").decimalValue()).isEqualByComparingTo("2000");
        assertThat(after.get("customers").asInt()).isEqualTo(1);
        Subscription kept = subscriptions.findByCustomerId(customer.getId()).orElseThrow();
        assertThat(kept.getCurrent().getTerms().planName()).isEqualTo("Test plan");
        assertThat(kept.getRevision()).isEqualTo(sold.getRevision());

        // a stale version is a conflict
        PUT(PLANS + "/gold-monthly", superToken, edit).andExpect(status().isConflict());
    }
}
