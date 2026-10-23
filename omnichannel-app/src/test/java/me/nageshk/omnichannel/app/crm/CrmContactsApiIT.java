package me.nageshk.omnichannel.app.crm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import me.nageshk.omnichannel.crm.activity.ActivityType;
import me.nageshk.omnichannel.crm.activity.CrmActivity;
import me.nageshk.omnichannel.crm.contact.ContactCounters;
import me.nageshk.omnichannel.db.contact.Contact;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import org.bson.Document;
import org.bson.types.Decimal128;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import tools.jackson.databind.JsonNode;

class CrmContactsApiIT extends CrmTestBase {

    @Autowired ContactCounters counters;

    String sales;
    Contact alice;
    String aliceId;

    @BeforeEach
    void setUp() throws Exception {
        enableCrm();
        sales = "seed-sales-" + brand.getId();
        alice = contact("Alice Wonder", "919811111111");
        aliceId = captureAs(alice, number);
    }

    private String captureAs(Contact c, me.nageshk.omnichannel.db.phone.PhoneNumber n) throws Exception {
        WaWebChat chat = chat(c, n, null, null);
        return json(send(post(crm() + "/contacts/capture"), managerToken, Map.of("chatId", chat.getId())))
                .get("contact").get("id").asText();
    }

    private JsonNode fetch(String id) throws Exception {
        return json(send(get(crm() + "/contacts/" + id), managerToken, null).andExpect(status().isOk()));
    }

    private JsonNode search(Map<String, Object> body, String token) throws Exception {
        return json(send(post(crm() + "/contacts/search"), token, body).andExpect(status().isOk()));
    }

    private Document rawDeal(String contactId, String pipelineId, String status, String amount) {
        Document d = new Document("workspaceId", brand.getId()).append("primaryCrmContactId", contactId)
                .append("crmContactIds", List.of(contactId)).append("pipelineId", pipelineId)
                .append("status", status);
        if (amount != null) d.append("amount", new Decimal128(new BigDecimal(amount)));
        return mongo.insert(d, "crmDeals");
    }

    @Test
    void searchFindsByNameAndPhone() throws Exception {
        String bobId = captureAs(contact("Bob Marley", "919833333333"), number2);
        JsonNode byName = search(Map.of("q", "wonder"), managerToken);
        assertThat(byName.get("total").asLong()).isEqualTo(1);
        assertThat(byName.get("items").get(0).get("id").asText()).isEqualTo(aliceId);
        JsonNode byPhone = search(Map.of("q", "9833333"), managerToken);
        assertThat(byPhone.get("items").get(0).get("id").asText()).isEqualTo(bobId);
        assertThat(search(Map.of(), managerToken).get("total").asLong()).isEqualTo(2);
        assertThat(search(Map.of("size", 1, "page", 1), managerToken).get("items")).hasSize(1);
        assertThat(search(Map.of("sort", "firstName", "dir", "asc"), managerToken).get("total").asLong())
                .isEqualTo(2);
        send(post(crm() + "/contacts/search"), managerToken, Map.of("sort", "bogus"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION"));
        send(post(crm() + "/contacts/search"), managerToken, Map.of("page", -1))
                .andExpect(status().isBadRequest());
    }

    @Test
    void searchFiltersByOwnerAndLifecycle() throws Exception {
        assertThat(search(Map.of("ownerId", manager.getId()), managerToken).get("total").asLong()).isEqualTo(1);
        assertThat(search(Map.of("ownerId", agent.getId()), managerToken).get("total").asLong()).isZero();
        assertThat(search(Map.of("lifecycleStatus", "lead"), managerToken).get("total").asLong()).isEqualTo(1);
        assertThat(search(Map.of("lifecycleStatus", "customer"), managerToken).get("total").asLong()).isZero();
    }

    @Test
    @Disabled("Task 10")
    void restrictedSearchSeesOnlyContactsInTheirPipelines() throws Exception {
        String bobId = captureAs(contact("Bob Marley", "919833333333"), number2);
        JsonNode second = json(send(post(crm() + "/pipelines"), managerToken, Map.of("name", "Second")));
        String p2 = second.get("id").asText();
        assignPipelines(agent, sales);
        // deals through the Task 10 endpoint: Alice in Sales, Bob in Second
        send(post(crm() + "/deals"), managerToken, Map.of("title", "A", "pipelineId", sales,
                "primaryCrmContactId", aliceId)).andExpect(status().is2xxSuccessful());
        send(post(crm() + "/deals"), managerToken, Map.of("title", "B", "pipelineId", p2,
                "primaryCrmContactId", bobId)).andExpect(status().is2xxSuccessful());
        JsonNode r = search(Map.of(), agentToken);
        assertThat(r.get("total").asLong()).isEqualTo(1);
        assertThat(r.get("items").get(0).get("id").asText()).isEqualTo(aliceId);
        send(get(crm() + "/contacts/" + bobId), agentToken, null).andExpect(status().isNotFound());
    }

    @Test
    void restrictedUserSeesNoContactsWithoutDealsAndDetailIs404() throws Exception {
        assignPipelines(agent, sales);
        assertThat(search(Map.of(), agentToken).get("total").asLong()).isZero();
        send(get(crm() + "/contacts/" + aliceId), agentToken, null).andExpect(status().isNotFound());
        // once a deal of an accessible pipeline exists (pipelineIds filled), the contact is visible
        rawDeal(aliceId, sales, "OPEN", "10");
        counters.recompute(brand.getId(), aliceId);
        assertThat(search(Map.of(), agentToken).get("total").asLong()).isEqualTo(1);
        send(get(crm() + "/contacts/" + aliceId), agentToken, null).andExpect(status().isOk());
    }

    private Map<String, Object> update(long version, Map<String, Object> fields) {
        Map<String, Object> body = new LinkedHashMap<>(fields);
        body.put("version", version);
        return body;
    }

    @Test
    void updateRecordsFieldChangedDiffs() throws Exception {
        long v = fetch(aliceId).get("version").asLong();
        JsonNode u = json(send(put(crm() + "/contacts/" + aliceId), managerToken,
                update(v, Map.of("firstName", "Alice", "email", "alice@example.com", "jobTitle", "CTO",
                        "lifecycleStatus", "prospect", "ownerId", agent.getId())))
                .andExpect(status().isOk()));
        assertThat(u.get("firstName").asText()).isEqualTo("Alice");
        assertThat(u.get("name").asText()).isEqualTo("Alice");
        assertThat(u.get("owner").get("id").asText()).isEqualTo(agent.getId());
        assertThat(u.get("version").asLong()).isGreaterThan(v);
        List<CrmActivity> acts = mongo.find(new Query(Criteria.where("type").is(ActivityType.FIELD_CHANGED)
                .and("target.id").is(aliceId)), CrmActivity.class);
        assertThat(acts).hasSize(1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> changes = (List<Map<String, Object>>) acts.get(0).getPayload().get("changes");
        assertThat(changes).extracting(d -> (String) d.get("field"))
                .containsExactlyInAnyOrder("firstName", "email", "jobTitle", "lifecycleStatus", "ownerId");
        Map<String, Object> life = changes.stream().filter(d -> d.get("field").equals("lifecycleStatus")).findFirst()
                .orElseThrow();
        assertThat(life.get("from")).isEqualTo("lead");
        assertThat(life.get("to")).isEqualTo("prospect");
        // blank clears, absent leaves
        JsonNode cleared = json(send(put(crm() + "/contacts/" + aliceId), managerToken,
                update(u.get("version").asLong(), Map.of("jobTitle", " "))).andExpect(status().isOk()));
        assertThat(cleared.get("jobTitle").isNull()).isTrue();
        assertThat(cleared.get("email").asText()).isEqualTo("alice@example.com");
    }

    @Test
    void staleVersionIs409() throws Exception {
        long v = fetch(aliceId).get("version").asLong();
        send(put(crm() + "/contacts/" + aliceId), managerToken, update(v, Map.of("firstName", "A")))
                .andExpect(status().isOk());
        send(put(crm() + "/contacts/" + aliceId), managerToken, update(v, Map.of("firstName", "B")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
    }

    @Test
    void updateValidation() throws Exception {
        long v = fetch(aliceId).get("version").asLong();
        send(put(crm() + "/contacts/" + aliceId), managerToken, Map.of("firstName", "A"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.version").exists());
        send(put(crm() + "/contacts/" + aliceId), managerToken, update(v, Map.of("firstName", "x".repeat(201))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.firstName").exists());
        send(put(crm() + "/contacts/" + aliceId), managerToken, update(v, Map.of("email", "e".repeat(255))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.email").exists());
        send(put(crm() + "/contacts/" + aliceId), managerToken, update(v, Map.of("jobTitle", "j".repeat(101))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.jobTitle").exists());
        send(put(crm() + "/contacts/" + aliceId), managerToken, update(v, Map.of("ownerId", staff.getId())))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.ownerId").exists());
        send(put(crm() + "/contacts/" + aliceId), managerToken, update(v, Map.of("source", "nope")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.source").exists());
        send(put(crm() + "/contacts/" + aliceId), agentToken, update(v, Map.of("firstName", "A")))
                .andExpect(status().isNotFound());
    }

    @Test
    void unknownLifecycleRejected() throws Exception {
        long v = fetch(aliceId).get("version").asLong();
        send(put(crm() + "/contacts/" + aliceId), managerToken, update(v, Map.of("lifecycleStatus", "vip")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION"))
                .andExpect(jsonPath("$.fields.lifecycleStatus").exists());
    }

    @Test
    void deletedPlatformContactShowsSnapshot() throws Exception {
        contacts.deleteById(alice.getId());
        JsonNode v = fetch(aliceId);
        assertThat(v.get("contactDeleted").asBoolean()).isTrue();
        assertThat(v.get("name").asText()).isEqualTo("Alice Wonder");
        assertThat(v.get("phone").asText()).isEqualTo("919811111111");
    }

    @Test
    void otherBrandContactIs404() throws Exception {
        send(get("/api/workspaces/" + other.getId() + "/crm/contacts/" + aliceId), managerToken, null)
                .andExpect(status().isNotFound());
        send(get(crm() + "/contacts/nope"), managerToken, null).andExpect(status().isNotFound());
    }

    @Test
    void countersRecomputeFromDeals() throws Exception {
        rawDeal(aliceId, sales, "OPEN", "100.50");
        rawDeal(aliceId, sales, "OPEN", "20");
        rawDeal(aliceId, "p2", "WON", "999");
        Document viaLinked = new Document("workspaceId", brand.getId()).append("primaryCrmContactId", "someone")
                .append("crmContactIds", List.of("someone", aliceId)).append("pipelineId", "p3")
                .append("status", "OPEN").append("amount", new Decimal128(new BigDecimal("5")));
        mongo.insert(viaLinked, "crmDeals");
        rawDeal("someone-else", "p9", "OPEN", "1");
        counters.recompute(brand.getId(), aliceId);
        Document c = mongo.findById(aliceId, Document.class, "crmContacts");
        assertThat(c.getInteger("openDealCount")).isEqualTo(3);
        assertThat(c.get("openDealAmount")).isInstanceOf(Decimal128.class);
        assertThat(((Decimal128) c.get("openDealAmount")).bigDecimalValue()).isEqualByComparingTo("125.50");
        assertThat(c.getList("pipelineIds", String.class)).containsExactlyInAnyOrder(sales, "p2", "p3");
        assertThat(c.getLong("version")).isEqualTo(0L);
        long version = fetch(aliceId).get("version").asLong();
        assertThat(version).isEqualTo(0L);
    }

    @Test
    @Disabled("Task 10")
    void countersForRestrictedUserCountOnlyAccessibleDeals() throws Exception {
        JsonNode second = json(send(post(crm() + "/pipelines"), managerToken, Map.of("name", "Second")));
        String p2 = second.get("id").asText();
        assignPipelines(agent, sales);
        send(post(crm() + "/deals"), managerToken, Map.of("title", "A", "pipelineId", sales, "amount", 10,
                "primaryCrmContactId", aliceId)).andExpect(status().is2xxSuccessful());
        send(post(crm() + "/deals"), managerToken, Map.of("title", "B", "pipelineId", p2, "amount", 90,
                "primaryCrmContactId", aliceId)).andExpect(status().is2xxSuccessful());
        JsonNode mine = json(send(get(crm() + "/contacts/" + aliceId), agentToken, null).andExpect(status().isOk()));
        assertThat(mine.get("openDealCount").asInt()).isEqualTo(1);
        assertThat(new BigDecimal(mine.get("openDealAmount").asText())).isEqualByComparingTo("10");
        JsonNode all = fetch(aliceId);
        assertThat(all.get("openDealCount").asInt()).isEqualTo(2);
        assertThat(new BigDecimal(all.get("openDealAmount").asText())).isEqualByComparingTo("100");
    }

    @Test
    void restrictedViewRecomputesCountersFromAccessibleDealsOnly() throws Exception {
        assignPipelines(agent, sales);
        rawDeal(aliceId, sales, "OPEN", "10");
        rawDeal(aliceId, "other-pipeline", "OPEN", "90");
        counters.recompute(brand.getId(), aliceId);
        JsonNode mine = json(send(get(crm() + "/contacts/" + aliceId), agentToken, null).andExpect(status().isOk()));
        assertThat(mine.get("openDealCount").asInt()).isEqualTo(1);
        assertThat(new BigDecimal(mine.get("openDealAmount").asText())).isEqualByComparingTo("10");
        JsonNode all = fetch(aliceId);
        assertThat(all.get("openDealCount").asInt()).isEqualTo(2);
        assertThat(all.get("channelHistory")).isEmpty();
    }
}
