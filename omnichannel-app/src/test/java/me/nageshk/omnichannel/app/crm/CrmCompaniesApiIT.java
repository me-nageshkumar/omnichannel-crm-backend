package me.nageshk.omnichannel.app.crm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
import me.nageshk.omnichannel.db.contact.Contact;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import org.bson.Document;
import org.bson.types.Decimal128;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import tools.jackson.databind.JsonNode;

class CrmCompaniesApiIT extends CrmTestBase {

    String aliceId;

    @BeforeEach
    void setUp() throws Exception {
        enableCrm();
        Contact alice = contact("Alice Wonder", "919811111111");
        WaWebChat chat = chat(alice, number, null, null);
        aliceId = json(send(post(crm() + "/contacts/capture"), managerToken, Map.of("chatId", chat.getId())))
                .get("contact").get("id").asText();
    }

    private JsonNode create(String name, Map<String, Object> more) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>(more);
        body.put("name", name);
        return json(send(post(crm() + "/companies"), managerToken, body).andExpect(status().isOk()));
    }

    private JsonNode company(String id) throws Exception {
        return json(send(get(crm() + "/companies/" + id), managerToken, null).andExpect(status().isOk()));
    }

    private void link(String contactId, String companyId) throws Exception {
        long v = json(send(get(crm() + "/contacts/" + contactId), managerToken, null)).get("version").asLong();
        send(put(crm() + "/contacts/" + contactId), managerToken, Map.of("version", v, "companyId", companyId))
                .andExpect(status().isOk());
    }

    private void rawDeal(String companyId, String pipelineId, String status, String amount) {
        mongo.insert(new Document("workspaceId", brand.getId()).append("companyId", companyId)
                .append("pipelineId", pipelineId).append("status", status)
                .append("amount", new Decimal128(new BigDecimal(amount))), "crmDeals");
    }

    @Test
    void createSearchAndTypeahead() throws Exception {
        JsonNode acme1 = create("Acme Corp", Map.of("domain", "acme.test", "ownerId", agent.getId(),
                "annualRevenue", "1250000.50", "address", Map.of("city", "Pune", "country", "IN")));
        create("Acorn Ltd", Map.of());
        create("Zeta (Pty) Ltd", Map.of());
        assertThat(acme1.get("name").asText()).isEqualTo("Acme Corp");
        assertThat(acme1.get("owner").get("id").asText()).isEqualTo(agent.getId());
        assertThat(acme1.get("address").get("city").asText()).isEqualTo("Pune");
        assertThat(acme1.get("annualRevenue").decimalValue()).isEqualByComparingTo("1250000.50");
        assertThat(acme1.get("contactCount").asInt()).isZero();
        String id = acme1.get("id").asText();

        Document stored = mongo.findOne(new Query(Criteria.where("_id").is(id)), Document.class, "crmCompanies");
        assertThat(stored.get("annualRevenue")).isInstanceOf(Decimal128.class);
        assertThat(stored.getString("customerId")).isEqualTo(acme.getId());
        assertThat(stored.getString("createdBy")).isEqualTo(manager.getId());
        assertThat(mongo.find(new Query(Criteria.where("type").is(ActivityType.RECORD_CREATED)
                .and("target.id").is(id)), CrmActivity.class)).hasSize(1);

        JsonNode all = json(send(post(crm() + "/companies/search"), agentToken, Map.of()).andExpect(status().isOk()));
        assertThat(all.get("total").asLong()).isEqualTo(3);
        assertThat(all.get("items").get(0).get("name").asText()).isEqualTo("Acme Corp");
        JsonNode q = json(send(post(crm() + "/companies/search"), managerToken, Map.of("q", "(pty")));
        assertThat(q.get("total").asLong()).isEqualTo(1);
        JsonNode owned = json(send(post(crm() + "/companies/search"), managerToken, Map.of("ownerId", agent.getId())));
        assertThat(owned.get("total").asLong()).isEqualTo(1);
        assertThat(json(send(post(crm() + "/companies/search"), managerToken, Map.of("size", 1, "page", 2)))
                .get("items")).hasSize(1);
        send(post(crm() + "/companies/search"), managerToken, Map.of("sort", "bogus"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION"));

        JsonNode ahead = json(send(get(crm() + "/companies/typeahead?q=ac"), agentToken, null)
                .andExpect(status().isOk()));
        assertThat(ahead).hasSize(2);
        assertThat(ahead.get(0).get("name").asText()).isEqualTo("Acme Corp");
        assertThat(ahead.get(0).get("id").asText()).isEqualTo(id);
        assertThat(json(send(get(crm() + "/companies/typeahead?q=.*"), agentToken, null))).isEmpty();
        assertThat(json(send(get(crm() + "/companies/typeahead?q=cme"), agentToken, null))).isEmpty();

        send(post(crm() + "/companies"), managerToken, Map.of("domain", "x")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION")).andExpect(jsonPath("$.fields.name").exists());
        send(post(crm() + "/companies"), agentToken, Map.of("name", "Nope")).andExpect(status().isOk());
        send(get(crm() + "/companies/nope"), managerToken, null).andExpect(status().isNotFound());
    }

    @Test
    void updateRecordsDiffsAndGuardsVersion() throws Exception {
        JsonNode c = create("Acme Corp", Map.of());
        String id = c.get("id").asText();
        long v = c.get("version").asLong();
        JsonNode u = json(send(put(crm() + "/companies/" + id), managerToken,
                Map.of("version", v, "name", "Acme Inc", "website", "https://acme.test")).andExpect(status().isOk()));
        assertThat(u.get("name").asText()).isEqualTo("Acme Inc");
        assertThat(u.get("version").asLong()).isGreaterThan(v);
        List<CrmActivity> acts = mongo.find(new Query(Criteria.where("type").is(ActivityType.FIELD_CHANGED)
                .and("target.id").is(id)), CrmActivity.class);
        assertThat(acts).hasSize(1);
        send(put(crm() + "/companies/" + id), managerToken, Map.of("version", v, "name", "Again"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        send(put(crm() + "/companies/nope"), managerToken, Map.of("version", v, "name", "Again"))
                .andExpect(status().isNotFound());
        send(put(crm() + "/companies/" + id), managerToken, Map.of("version", u.get("version").asLong(), "name", " "))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.name").exists());
        send(put(crm() + "/companies/" + id), managerToken, Map.of("name", "x"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.version").exists());
    }

    @Test
    void industryMustBeActivePicklistKey() throws Exception {
        JsonNode s = json(send(get(crm() + "/settings"), managerToken, null));
        Map<String, Object> body = mapper.convertValue(s,
                new tools.jackson.core.type.TypeReference<LinkedHashMap<String, Object>>() {});
        body.put("industries", List.of(Map.of("key", "retail", "label", "Retail", "color", "#111111", "position", 1,
                "active", true, "isDefault", false)));
        send(put(crm() + "/settings"), managerToken, body).andExpect(status().isOk());
        send(post(crm() + "/companies"), managerToken, Map.of("name", "A", "industry", "mining"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.industry").exists());
        send(post(crm() + "/companies"), managerToken, Map.of("name", "A", "industry", "retail"))
                .andExpect(status().isOk());
        send(post(crm() + "/companies"), managerToken, Map.of("name", "A", "ownerId", "stranger"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.ownerId").exists());
    }

    @Test
    void linkingContactUpdatesContactCount() throws Exception {
        String id = create("Acme Corp", Map.of()).get("id").asText();
        String other = create("Other Co", Map.of()).get("id").asText();
        link(aliceId, id);
        assertThat(company(id).get("contactCount").asInt()).isEqualTo(1);
        JsonNode contact = json(send(get(crm() + "/contacts/" + aliceId), managerToken, null));
        assertThat(contact.get("company").get("id").asText()).isEqualTo(id);
        assertThat(contact.get("company").get("name").asText()).isEqualTo("Acme Corp");
        assertThat(json(send(post(crm() + "/contacts/search"), managerToken, Map.of("companyId", id)))
                .get("total").asLong()).isEqualTo(1);
        assertThat(company(id).get("version").asLong()).isEqualTo(0); // counters never bump the version

        link(aliceId, other);
        assertThat(company(id).get("contactCount").asInt()).isZero();
        assertThat(company(other).get("contactCount").asInt()).isEqualTo(1);

        long v = json(send(get(crm() + "/contacts/" + aliceId), managerToken, null)).get("version").asLong();
        send(put(crm() + "/contacts/" + aliceId), managerToken, Map.of("version", v, "companyId", "ghost"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.companyId").exists());
        JsonNode cleared = json(send(put(crm() + "/contacts/" + aliceId), managerToken,
                Map.of("version", v, "companyId", " ")).andExpect(status().isOk()));
        assertThat(cleared.get("company").isNull()).isTrue();
        assertThat(company(other).get("contactCount").asInt()).isZero();
    }

    @Test
    void summaryListsLinkedContactsAndCounters() throws Exception {
        String id = create("Acme Corp", Map.of()).get("id").asText();
        link(aliceId, id);
        rawDeal(id, "p", "OPEN", "40.5");
        rawDeal(id, "p", "WON", "99");
        JsonNode s = json(send(get(crm() + "/companies/" + id + "/summary"), managerToken, null)
                .andExpect(status().isOk()));
        assertThat(s.get("company").get("id").asText()).isEqualTo(id);
        assertThat(s.get("contacts")).hasSize(1);
        assertThat(s.get("contacts").get(0).get("id").asText()).isEqualTo(aliceId);
        assertThat(s.get("contactCount").asInt()).isEqualTo(1);
        assertThat(s.get("openDealAmount").decimalValue()).isEqualByComparingTo("40.5");
        // a restricted user without pipelines sees no contacts of the company
        JsonNode r = json(send(get(crm() + "/companies/" + id + "/summary"), agentToken, null)
                .andExpect(status().isOk()));
        assertThat(r.get("contacts")).isEmpty();
    }

    @Test
    @Disabled("Task 10")
    void deleteWithOpenDealsIs409() throws Exception {
        String id = create("Acme Corp", Map.of()).get("id").asText();
        link(aliceId, id);
        rawDeal(id, "p", "OPEN", "10");
        send(delete(crm() + "/companies/" + id), managerToken, null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COMPANY_HAS_OPEN_DEALS"));
        assertThat(mongo.exists(new Query(Criteria.where("_id").is(id)), "crmCompanies")).isTrue();
        // a closed deal does not block
        mongo.remove(new Query(), "crmDeals");
        rawDeal(id, "p", "WON", "10");
        send(delete(crm() + "/companies/" + id), managerToken, null).andExpect(status().isNoContent());
    }

    @Test
    void deleteUnlinksContacts() throws Exception {
        String id = create("Acme Corp", Map.of()).get("id").asText();
        link(aliceId, id);
        send(delete(crm() + "/companies/" + id), managerToken, null).andExpect(status().isNoContent());
        send(get(crm() + "/companies/" + id), managerToken, null).andExpect(status().isNotFound());
        JsonNode contact = json(send(get(crm() + "/contacts/" + aliceId), managerToken, null));
        assertThat(contact.get("company").isNull()).isTrue();
        Document raw = mongo.findOne(new Query(Criteria.where("_id").is(aliceId)), Document.class, "crmContacts");
        assertThat(raw.containsKey("companyId")).isFalse();
        send(delete(crm() + "/companies/" + id), managerToken, null).andExpect(status().isNotFound());
    }

    @Test
    void agentCannotDelete() throws Exception {
        String id = create("Acme Corp", Map.of()).get("id").asText();
        send(delete(crm() + "/companies/" + id), agentToken, null).andExpect(status().isForbidden());
        assertThat(mongo.exists(new Query(Criteria.where("_id").is(id)), "crmCompanies")).isTrue();
    }

    @Test
    @Disabled("Task 10")
    void restrictedSummaryHidesInaccessibleDeals() throws Exception {
        String id = create("Acme Corp", Map.of()).get("id").asText();
        link(aliceId, id);
        String sales = "seed-sales-" + brand.getId();
        String p2 = json(send(post(crm() + "/pipelines"), managerToken, Map.of("name", "Second"))).get("id").asText();
        rawDeal(id, sales, "OPEN", "100");
        rawDeal(id, p2, "OPEN", "50");
        mongo.updateFirst(new Query(Criteria.where("_id").is(aliceId)),
                new org.springframework.data.mongodb.core.query.Update().set("pipelineIds", List.of(sales, p2)),
                "crmContacts");
        assignPipelines(agent, sales);
        JsonNode r = json(send(get(crm() + "/companies/" + id + "/summary"), agentToken, null)
                .andExpect(status().isOk()));
        assertThat(r.get("contacts")).hasSize(1);
        assertThat(r.get("openDealAmount").decimalValue()).isEqualByComparingTo("100");
        JsonNode m = json(send(get(crm() + "/companies/" + id + "/summary"), managerToken, null));
        assertThat(m.get("openDealAmount").decimalValue()).isEqualByComparingTo("150");
    }
}
