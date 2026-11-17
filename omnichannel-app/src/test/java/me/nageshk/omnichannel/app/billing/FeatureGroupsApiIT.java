package me.nageshk.omnichannel.app.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.nageshk.omnichannel.db.access.AuthorityDefinitionRepository;
import me.nageshk.omnichannel.security.access.AuthorityCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

class FeatureGroupsApiIT extends BillingApiTestBase {

    static final String GROUPS = "/api/billing/feature-groups";

    @Autowired AuthorityDefinitionRepository authorities;
    @Autowired AuthorityCatalog catalog;

    private Map<String, Object> body(JsonNode group) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("name", group.get("name").asString());
        b.put("description", group.get("description").asString());
        b.put("category", group.get("category").asString());
        b.put("authorities", new ArrayList<>(strings(group.get("authorities"))));
        b.put("requires", new ArrayList<>(strings(group.get("requires"))));
        b.put("sortOrder", group.get("sortOrder").asInt());
        b.put("version", group.get("version").asLong());
        return b;
    }

    private JsonNode group(String code) throws Exception {
        for (JsonNode g : json(GET(GROUPS, superToken))) if (g.get("code").asString().equals(code)) return g;
        throw new AssertionError("no group " + code);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> without(JsonNode group, String authority) {
        Map<String, Object> b = body(group);
        ((List<String>) b.get("authorities")).remove(authority);
        return b;
    }

    @Test
    void listsTheSeededGroupsInOrder() throws Exception {
        JsonNode all = json(GET(GROUPS, superToken).andExpect(status().isOk()));
        List<String> codes = new ArrayList<>();
        all.forEach(g -> codes.add(g.get("code").asString()));
        assertThat(codes).containsExactly("core", "inbox", "contacts", "templates", "campaigns", "automations",
                "chatbots", "ai", "crm");
        assertThat(group("core").get("builtIn").asBoolean()).isTrue();
        assertThat(group("core").get("category").asString()).isEqualTo("DEFAULT");
        assertThat(strings(group("ai").get("requires"))).containsExactly("chatbots");
        GET(GROUPS + "/ungrouped", superToken).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void anAccountManagerReadsButDoesNotWrite() throws Exception {
        GET(GROUPS, amToken).andExpect(status().isOk());
        POST(GROUPS, amToken, Map.of("code", "extras", "name", "Extras", "category", "PAID")).andExpect(status().isForbidden());
        GET(GROUPS, adminToken).andExpect(status().isForbidden());
    }

    @Test
    void createsUpdatesAndKeepsHistory() throws Exception {
        JsonNode crm = group("crm");
        PUT(GROUPS + "/crm", superToken, without(crm, "crm:export")).andExpect(status().isOk());
        GET(GROUPS + "/ungrouped", superToken).andExpect(jsonPath("$[0].code").value("crm:export"))
                .andExpect(jsonPath("$[0].level").value("BRAND"));

        JsonNode made = json(POST(GROUPS, superToken, Map.of("code", "exports", "name", "Exports",
                "description", "CSV exports", "category", "PAID", "authorities", List.of("crm:export"),
                "requires", List.of("crm"), "sortOrder", 95)).andExpect(status().isCreated()));
        assertThat(made.get("builtIn").asBoolean()).isFalse();
        assertThat(strings(made.get("authorities"))).containsExactly("crm:export");

        // the version read before the edit above is stale now
        PUT(GROUPS + "/crm", superToken, body(crm)).andExpect(status().isConflict());

        JsonNode history = json(GET(GROUPS + "/crm/history", superToken).andExpect(status().isOk()));
        assertThat(history).hasSize(1);
        assertThat(history.get(0).get("byName").asString()).isNotBlank();
        assertThat(history.get(0).get("summary").asString()).contains("crm:export");

        DELETE(GROUPS + "/exports", superToken).andExpect(status().isNoContent());
        GET(GROUPS + "/ungrouped", superToken).andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void refusesWhatWouldBreakTheGroups() throws Exception {
        // an authority that another group holds
        POST(GROUPS, superToken, Map.of("code", "dupe", "name", "Dupe", "category", "PAID",
                "authorities", List.of("crm:view"))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.authorities").value("crm:view is already in group CRM"));
        // a platform authority, and one that does not exist
        POST(GROUPS, superToken, Map.of("code", "plat", "name", "Plat", "category", "PAID",
                "authorities", List.of("customer:view"))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.authorities").exists());
        POST(GROUPS, superToken, Map.of("code", "ghost", "name", "Ghost", "category", "PAID",
                "authorities", List.of("nothing:here"))).andExpect(status().isBadRequest());
        // a bad code, a taken code, a missing name
        POST(GROUPS, superToken, Map.of("code", "Bad Code", "name", "X", "category", "PAID"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.code").exists());
        POST(GROUPS, superToken, Map.of("code", "crm", "name", "X", "category", "PAID"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.code").exists());
        POST(GROUPS, superToken, Map.of("code", "noname", "name", " ", "category", "PAID"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.name").exists());
        // requires: unknown, itself, a cycle
        POST(GROUPS, superToken, Map.of("code", "needy", "name", "Needy", "category", "PAID",
                "requires", List.of("nope"))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.requires").exists());
        Map<String, Object> chatbots = body(group("chatbots"));
        chatbots.put("requires", List.of("ai"));
        PUT(GROUPS + "/chatbots", superToken, chatbots).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.requires").exists());
    }

    @Test
    void coreIsProtected() throws Exception {
        JsonNode core = group("core");
        PUT(GROUPS + "/core", superToken, without(core, "user:manage")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.authorities").exists());
        Map<String, Object> paid = body(core);
        paid.put("category", "PAID");
        PUT(GROUPS + "/core", superToken, paid).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.category").exists());
        DELETE(GROUPS + "/core", superToken).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BUILT_IN"));
    }

    @Test
    void aGroupAPlanListsCannotChangeCategoryOrGo() throws Exception {
        POST(GROUPS, superToken, Map.of("code", "extras", "name", "Extras", "category", "PAID"))
                .andExpect(status().isCreated());
        Map<String, Object> plan = planBody("gold-monthly");
        plan.put("items", List.of(Map.of("groupCode", "extras", "price", 10)));
        plan.put("addOns", List.of());
        POST("/api/billing/plans", superToken, plan).andExpect(status().isCreated());

        assertThat(strings(group("extras").get("planCodes"))).containsExactly("gold-monthly");
        Map<String, Object> asDefault = body(group("extras"));
        asDefault.put("category", "DEFAULT");
        PUT(GROUPS + "/extras", superToken, asDefault).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("GROUP_IN_USE")).andExpect(jsonPath("$.plans").value("gold-monthly"));
        DELETE(GROUPS + "/extras", superToken).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("GROUP_IN_USE"));
    }

    @Test
    void aGroupEditReachesASubscribedCustomerAtOnce() throws Exception {
        billing.assign(customer.getId(), Set.of("crm"), Map.of());
        assertThat(strings(json(GET("/api/users/me", adminToken)).at("/workspaces/0/authorities"))).contains("crm:export");

        PUT(GROUPS + "/crm", superToken, without(group("crm"), "crm:export")).andExpect(status().isOk());

        assertThat(strings(json(GET("/api/users/me", adminToken)).at("/workspaces/0/authorities")))
                .contains("crm:view").doesNotContain("crm:export");
    }

    @Test
    void anAuthorityDeletedFromTheCatalogueBreaksNothing() throws Exception {
        billing.assign(customer.getId(), Set.of("crm"), Map.of());
        authorities.deleteById("crm:export");
        catalog.evictAll();

        GET(GROUPS, superToken).andExpect(status().isOk());
        assertThat(strings(json(GET("/api/users/me", adminToken)).at("/workspaces/0/authorities")))
                .contains("crm:view").doesNotContain("crm:export");
        // and the group can still be saved with the dead code dropped
        PUT(GROUPS + "/crm", superToken, without(group("crm"), "crm:export")).andExpect(status().isOk());
    }

    @Test
    void nothingCanRequireAGroupThatIsInEveryPlan() throws Exception {
        POST(GROUPS, superToken, Map.of("code", "needs-inbox", "name", "Needs inbox", "category", "PAID",
                "requires", List.of("inbox"))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.requires").value("Inbox is in every plan already; nothing needs to require it"));
    }
}
