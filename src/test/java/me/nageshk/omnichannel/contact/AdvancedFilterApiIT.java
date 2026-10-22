package me.nageshk.omnichannel.contact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.MediaType;

class AdvancedFilterApiIT extends ContactApiTestBase {

    ContactLabel vip;
    ContactAttribute city;

    @BeforeEach
    void data() {
        vip = label(brandA, "VIP");
        city = attribute(brandA, "City");
    }

    @AfterEach
    void cleanFilters() {
        filters.deleteAll();
        mongo.remove(new Query(), "campaignSettings");
    }

    String filtersUrl() {
        return base(brandA) + "/contact-filters";
    }

    static Map<String, Object> group(String combinator, Object... rules) {
        return Map.of("combinator", combinator, "not", false, "rules", List.of(rules));
    }

    static Map<String, Object> rule(String field, String operator, Object value) {
        return Map.of("field", field, "operator", operator, "value", value);
    }

    Map<String, Object> pune() {
        return group("and", rule("labels", "has", vip.getId()), rule("attributes." + city.getId(), "is", "Pune"));
    }

    String createAdvanced(String name, Map<String, Object> rules) throws Exception {
        return json(send(post(filtersUrl()), leadToken, Map.of("name", name, "rules", rules))
                .andExpect(status().isCreated())).get("id").asString();
    }

    String createSimple(String name) throws Exception {
        return json(send(post(filtersUrl()), leadToken, Map.of("name", name, "filter", Map.of("q", "x")))
                .andExpect(status().isCreated())).get("id").asString();
    }

    @Test
    void createStoresRulesQueryAndUses() throws Exception {
        String id = json(send(post(filtersUrl()), leadToken, Map.of("name", "Pune VIPs", "rules", pune()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.kind").value("ADVANCED"))
                .andExpect(jsonPath("$.filter").doesNotExist())
                .andExpect(jsonPath("$.rules.rules[0].value").value(vip.getId()))
                .andExpect(jsonPath("$.query['$and'][0].labels").value(vip.getId()))
                .andExpect(jsonPath("$.query['$and'][1]['attributes." + city.getId() + "']['$regex']")
                        .value("^Pune$"))
                .andExpect(jsonPath("$.uses.labelIds[0]").value(vip.getId()))
                .andExpect(jsonPath("$.uses.attributeIds[0]").value(city.getId()))).get("id").asString();
        ContactFilter stored = filters.findById(id).orElseThrow();
        assertThat(stored.getQueryVersion()).isEqualTo(1);
        assertThat(stored.getQueryJson()).isNotNull();
        assertThat(stored.getKind()).isEqualTo(FilterKind.ADVANCED);
    }

    @Test
    void getReturnsTheFullFilter() throws Exception {
        String adv = createAdvanced("Adv", pune());
        String simple = createSimple("Simple");
        send(get(filtersUrl() + "/" + adv), agentToken, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("ADVANCED"))
                .andExpect(jsonPath("$.rules.rules.length()").value(2))
                .andExpect(jsonPath("$.query['$and']").isArray())
                .andExpect(jsonPath("$.uses.labelIds[0]").value(vip.getId()));
        send(get(filtersUrl() + "/" + simple), agentToken, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("SIMPLE"))
                .andExpect(jsonPath("$.filter.q").value("x"))
                .andExpect(jsonPath("$.rules").doesNotExist())
                .andExpect(jsonPath("$.query").doesNotExist())
                .andExpect(jsonPath("$.uses").doesNotExist());
        send(get("/api/workspaces/" + brandB.getId() + "/contact-filters/" + adv), wmBToken, null)
                .andExpect(status().isNotFound());
    }

    @Test
    void listShowsBothKinds() throws Exception {
        createSimple("A simple");
        createAdvanced("B advanced", pune());
        send(get(filtersUrl()), agentToken, null).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].kind").value("SIMPLE"))
                .andExpect(jsonPath("$[1].kind").value("ADVANCED"))
                .andExpect(jsonPath("$[1].rules").isNotEmpty())
                .andExpect(jsonPath("$[1].query").isNotEmpty());
    }

    @Test
    void exactlyOneOfFilterOrRules() throws Exception {
        send(post(filtersUrl()), leadToken, Map.of("name", "Both", "filter", Map.of(), "rules", pune()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.filter").value("give exactly one of filter or rules"));
        send(post(filtersUrl()), leadToken, Map.of("name", "Neither"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.filter").value("give exactly one of filter or rules"));
    }

    @Test
    void kindCannotChange() throws Exception {
        String simple = createSimple("S");
        String adv = createAdvanced("A", pune());
        send(put(filtersUrl() + "/" + simple), leadToken, Map.of("name", "S", "rules", pune()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.rules").value("the kind of a saved filter cannot change"));
        send(put(filtersUrl() + "/" + adv), leadToken, Map.of("name", "A", "filter", Map.of()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.filter").value("the kind of a saved filter cannot change"));
    }

    @Test
    void updateRecompiles() throws Exception {
        String id = createAdvanced("A", pune());
        Map<String, Object> body = new HashMap<>();
        body.put("name", "A");
        body.put("rules", group("and", rule("name", "startsWith", "As")));
        body.put("query", Map.of("$where", "1"));
        send(put(filtersUrl() + "/" + id), leadToken, body).andExpect(status().isOk())
                .andExpect(jsonPath("$.query['$and'][0].name['$regex']").value("^As"))
                .andExpect(jsonPath("$.uses.labelIds.length()").value(0));
        assertThat(filters.findById(id).orElseThrow().getQueryJson()).doesNotContain("$where");
    }

    @Test
    void validationErrorsCarryThePath() throws Exception {
        Map<String, Object> rules = group("and", rule("name", "is", "A"),
                group("or", rule("labels", "has", "nope")));
        send(post(filtersUrl()), leadToken, Map.of("name", "Bad", "rules", rules))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION"))
                .andExpect(jsonPath("$.fields['rules[1].rules[0].value']").value("is not a label of this brand"));
    }

    @Test
    void builderShapedPayloadIsAccepted() throws Exception {
        Map<String, Object> rules = Map.of("id", "g-1", "combinator", "and", "not", false, "rules", List.of(
                Map.of("id", "r-1", "field", "name", "operator", "is", "value", "A", "valueSource", "value"),
                Map.of("id", "g-2", "combinator", "or", "rules", List.of(
                        Map.of("id", "r-2", "field", "lastMessageAt", "operator", "isSet", "value", ""),
                        Map.of("id", "r-3", "field", "phone", "operator", "contains", "value", "98",
                                "valueSource", "value")))));
        send(post(filtersUrl()), leadToken, Map.of("name", "Builder", "rules", rules))
                .andExpect(status().isCreated());
    }

    @Test
    void legacyFilterWithoutKindReadsAsSimple() throws Exception {
        ContactFilter f = new ContactFilter();
        f.setWorkspaceId(brandA.getId());
        f.setName("Legacy");
        f.setFilter(new ContactFilterSpec(null, null, null, null, null, null, null, null, null, null, null, null));
        f.setCreatedBy("x");
        f = filters.save(f);
        send(get(filtersUrl()), agentToken, null).andExpect(jsonPath("$[0].kind").value("SIMPLE"));
        send(put(filtersUrl() + "/" + f.getId()), leadToken, Map.of("name", "Legacy", "filter", Map.of()))
                .andExpect(status().isOk());
        assertThat(filters.findById(f.getId()).orElseThrow().getKind()).isEqualTo(FilterKind.SIMPLE);
    }

    @Test
    void datesUseTheWorkspaceTimezone() throws Exception {
        Map<String, Object> rules = group("and", rule("creationTime", "after", "2026-10-20"));
        String id = createAdvanced("Dates", rules);
        send(get(filtersUrl() + "/" + id), agentToken, null)
                .andExpect(jsonPath("$.query['$and'][0].creationTime['$gte']['$date']")
                        .value("2026-10-20T18:30:00Z"));
        mongo.updateFirst(Query.query(Criteria.where("workspaceId").is(brandA.getId())),
                new Update().set("timezone", "UTC"), "campaignSettings");
        send(put(filtersUrl() + "/" + id), leadToken, Map.of("name", "Dates", "rules", rules))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.query['$and'][0].creationTime['$gte']['$date']")
                        .value("2026-10-21T00:00:00Z"));
    }

    @Test
    void savingNeedsContactBulk() throws Exception {
        String id = createAdvanced("A", pune());
        send(post(filtersUrl()), agentToken, Map.of("name", "N", "rules", pune())).andExpect(status().isForbidden());
        send(put(filtersUrl() + "/" + id), agentToken, Map.of("name", "A", "rules", pune()))
                .andExpect(status().isForbidden());
    }

    @Test
    void deleteAnAdvancedFilter() throws Exception {
        String id = createAdvanced("A", pune());
        send(delete(filtersUrl() + "/" + id), wmToken, null).andExpect(status().isNoContent());
        send(get(filtersUrl() + "/" + id), agentToken, null).andExpect(status().isNotFound());
        send(post(base(brandA) + "/contacts/search"), agentToken, Map.of("filterId", id))
                .andExpect(status().isNotFound());
    }

    @Test
    void deeplyNestedBodyIsA400() throws Exception {
        int depth = 1000;
        String leaf = "{\"field\":\"name\",\"operator\":\"is\",\"value\":\"A\"}";
        String body = "{\"name\":\"Deep\",\"rules\":" + "{\"combinator\":\"and\",\"rules\":[".repeat(depth) + leaf
                + "]}".repeat(depth) + "}";
        mvc.perform(post(filtersUrl()).header("Authorization", "Bearer " + leadToken)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
    }
}
