package me.nageshk.omnichannel.app.crm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.Map;
import me.nageshk.omnichannel.crm.activity.ActivityType;
import me.nageshk.omnichannel.crm.activity.ActorType;
import me.nageshk.omnichannel.crm.activity.CrmActivity;
import me.nageshk.omnichannel.crm.common.CrmTarget;
import me.nageshk.omnichannel.crm.common.TargetType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import tools.jackson.databind.JsonNode;

/** The settings history (spec F C16, C17). */
class SettingsHistoryIT extends CrmTestBase {

    @BeforeEach
    void enable() {
        enableCrm();
    }

    private Map<String, Object> settings() throws Exception {
        return new HashMap<>(mapper.convertValue(json(send(get(crm() + "/settings"), managerToken, null)
                .andExpect(status().isOk())), new tools.jackson.core.type.TypeReference<Map<String, Object>>() {}));
    }

    private void save(Map<String, Object> body) throws Exception {
        send(put(crm() + "/settings"), managerToken, body).andExpect(status().isOk());
    }

    private JsonNode history(String query) throws Exception {
        return json(send(get(crm() + "/settings/history" + query), managerToken, null).andExpect(status().isOk()));
    }

    private void rawEntry(String workspaceId, String summary) {
        CrmActivity a = new CrmActivity();
        a.setWorkspaceId(workspaceId);
        a.setCustomerId(acme.getId());
        a.setType(ActivityType.SETTINGS_CHANGED);
        a.setTarget(new CrmTarget(TargetType.SETTINGS, workspaceId));
        a.setActorId(manager.getId());
        a.setActorType(ActorType.USER);
        a.setHappenedAt(java.time.Instant.now());
        a.setSummary(summary);
        a.setPayload(new java.util.LinkedHashMap<>());
        mongo.insert(a);
    }

    @Test
    void historyListsChangesNewestFirst() throws Exception {
        Map<String, Object> body = settings();
        body.put("dealRottingDays", 20);
        save(body);
        body = settings();
        body.put("defaultCurrency", "USD");
        save(body);
        JsonNode page = history("");
        assertThat(page.get("total").asLong()).isEqualTo(2);
        assertThat(page.get("items").get(0).get("summary").asText()).isEqualTo("Updated CRM settings: default currency");
        assertThat(page.get("items").get(1).get("summary").asText()).isEqualTo("Updated CRM settings: rotting days");
        assertThat(page.get("items").get(0).get("actor").get("id").asText()).isEqualTo(manager.getId());
    }

    @Test
    void summaryNamesWhatChanged() throws Exception {
        Map<String, Object> body = settings();
        body.put("defaultCurrency", "USD");
        body.put("allowChannelMove", !Boolean.TRUE.equals(body.get("allowChannelMove")));
        save(body);
        JsonNode entry = history("").get("items").get(0);
        assertThat(entry.get("summary").asText()).isEqualTo("Updated CRM settings: default currency, channel moves");
        assertThat(entry.get("payload").get("changed").valueStream().map(JsonNode::asText).toList())
                .containsExactly("defaultCurrency", "allowChannelMove");
    }

    @Test
    void saveWithoutChangesRecordsNothing() throws Exception {
        Map<String, Object> body = settings();
        long version = ((Number) body.get("version")).longValue();
        save(body);
        assertThat(history("").get("total").asLong()).isZero();
        assertThat(((Number) settings().get("version")).longValue()).isEqualTo(version + 1);
    }

    @Test
    void aPersonWithABlankNameReadsAsTheirUsername() throws Exception {
        assignPipelines(agent, salesId());
        mongo.updateFirst(new Query(Criteria.where("_id").is(new org.bson.types.ObjectId(agent.getId()))),
                new org.springframework.data.mongodb.core.query.Update().set("name", "  "), "users");
        assertThat(history("").get("items").get(0).get("summary").asText())
                .isEqualTo("Changed CRM pipelines of " + agent.getUsername());
    }

    @Test
    void pipelineChangeNamesThePerson() throws Exception {
        assignPipelines(agent, salesId());
        JsonNode entry = history("").get("items").get(0);
        assertThat(entry.get("summary").asText()).isEqualTo("Changed CRM pipelines of " + agent.getName());
        assertThat(entry.get("summary").asText()).doesNotContain(agent.getId());
    }

    @Test
    void olderEntryStillReads() throws Exception {
        rawEntry(brand.getId(), "Updated CRM settings");
        assertThat(history("").get("items").get(0).get("summary").asText()).isEqualTo("Updated CRM settings");
    }

    @Test
    void agentGets403() throws Exception {
        send(get(crm() + "/settings/history"), agentToken, null).andExpect(status().isForbidden());
    }

    @Test
    void otherBrandIsNotListed() throws Exception {
        rawEntry(other.getId(), "Updated CRM settings");
        assertThat(history("").get("total").asLong()).isZero();
    }

    @Test
    void pagesAndCaps() throws Exception {
        for (int i = 0; i < 3; i++) rawEntry(brand.getId(), "Entry " + i);
        JsonNode second = history("?page=1&size=2");
        assertThat(second.get("items")).hasSize(1);
        assertThat(second.get("total").asLong()).isEqualTo(3);
        send(get(crm() + "/settings/history?size=0"), managerToken, null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.size").exists());
    }

    @Test
    void nonSettingsEntriesAreNotListed() throws Exception {
        captured(contact("Asha", "919811111301"), number);
        assertThat(mongo.count(new Query(Criteria.where("workspaceId").is(brand.getId())), CrmActivity.class)).isPositive();
        assertThat(history("").get("items")).isEmpty();
    }

}
