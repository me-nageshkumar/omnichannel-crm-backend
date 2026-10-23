package me.nageshk.omnichannel.app.crm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import me.nageshk.omnichannel.crm.settings.CrmSettings;
import tools.jackson.databind.JsonNode;

class CrmSettingsApiIT extends CrmTestBase {

    @BeforeEach
    void on() {
        enableCrm();
    }

    private JsonNode read() throws Exception {
        return json(send(get(crm() + "/settings"), managerToken, null).andExpect(status().isOk()));
    }

    /** The current view as an editable request map. */
    private Map<String, Object> editable() throws Exception {
        JsonNode v = read();
        return mapper.convertValue(v, new tools.jackson.core.type.TypeReference<LinkedHashMap<String, Object>>() {});
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> list(Map<String, Object> body, String name) {
        return (List<Map<String, Object>>) body.get(name);
    }

    @Test
    void firstReadCreatesDefaultsAndSalesPipeline() throws Exception {
        JsonNode v = read();
        assertThat(v.get("defaultCurrency").asText()).isEqualTo("INR");
        assertThat(v.get("dealRottingDays").asInt()).isEqualTo(14);
        assertThat(v.get("allowChannelMove").asBoolean()).isTrue();
        assertThat(v.get("lifecycleStatuses")).hasSize(4);
        assertThat(v.get("dealSources")).hasSize(5);
        assertThat(v.get("taskTypes")).hasSize(5);
        assertThat(v.get("lostReasons")).hasSize(5);
        assertThat(v.get("industries")).isEmpty();
        assertThat(v.get("lifecycleStatuses").get(0).get("key").asText()).isEqualTo("lead");
        assertThat(v.get("lifecycleStatuses").get(0).get("isDefault").asBoolean()).isTrue();
        assertThat(v.has("enabled")).isFalse();
        assertThat(v.has("maxPipelines")).isFalse();
    }

    @Test
    void managerUpdatesPicklistsAndCurrency() throws Exception {
        Map<String, Object> body = editable();
        body.put("defaultCurrency", "USD");
        body.put("dealRottingDays", 30);
        body.put("allowChannelMove", false);
        list(body, "industries").add(new LinkedHashMap<>(Map.of("key", "retail", "label", "Retail", "color", "#111111",
                "position", 1, "active", true, "isDefault", false)));
        list(body, "dealSources").get(0).put("active", false);
        JsonNode v = json(send(put(crm() + "/settings"), managerToken, body).andExpect(status().isOk()));
        assertThat(v.get("defaultCurrency").asText()).isEqualTo("USD");
        assertThat(v.get("dealRottingDays").asInt()).isEqualTo(30);
        assertThat(v.get("allowChannelMove").asBoolean()).isFalse();
        assertThat(v.get("industries")).hasSize(1);
        assertThat(v.get("dealSources").get(0).get("active").asBoolean()).isFalse();
        assertThat(v.get("version").asLong()).isEqualTo(body.get("version") instanceof Number n ? n.longValue() + 1 : -1);
        CrmSettings stored = mongo.findOne(new Query(Criteria.where("workspaceId").is(brand.getId())), CrmSettings.class);
        assertThat(stored.isEnabled()).isTrue();
        assertThat(stored.getLastModifiedBy()).isEqualTo(manager.getId());
        assertThat(stored.getCreatedBy()).isEqualTo(staff.getId());
    }

    @Test
    void staleVersionIs409() throws Exception {
        Map<String, Object> body = editable();
        send(put(crm() + "/settings"), managerToken, body).andExpect(status().isOk());
        send(put(crm() + "/settings"), managerToken, body).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
    }

    @Test
    void removingPicklistKeyRejected() throws Exception {
        Map<String, Object> body = editable();
        list(body, "taskTypes").remove(0);
        send(put(crm() + "/settings"), managerToken, body).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION"))
                .andExpect(jsonPath("$.fields.taskTypes").exists());
    }

    @Test
    void badKeyAndDuplicateKeyRejected() throws Exception {
        Map<String, Object> body = editable();
        list(body, "industries").add(new LinkedHashMap<>(Map.of("key", "Bad Key", "label", "x", "position", 1,
                "active", true, "isDefault", false)));
        send(put(crm() + "/settings"), managerToken, body).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields['industries[0].key']").exists());
        Map<String, Object> dup = editable();
        List<Map<String, Object>> ind = list(dup, "industries");
        ind.add(new LinkedHashMap<>(Map.of("key", "a", "label", "A", "position", 1, "active", true, "isDefault", false)));
        ind.add(new LinkedHashMap<>(Map.of("key", "a", "label", "B", "position", 2, "active", true, "isDefault", false)));
        send(put(crm() + "/settings"), managerToken, dup).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields['industries[1].key']").exists());
    }

    @Test
    void agentCannotUpdate() throws Exception {
        Map<String, Object> body = editable();
        send(put(crm() + "/settings"), agentToken, body).andExpect(status().isForbidden());
        send(get(crm() + "/settings"), agentToken, null).andExpect(status().isOk());
    }

    @Test
    void twoDefaultsRejected() throws Exception {
        Map<String, Object> body = editable();
        list(body, "lifecycleStatuses").get(1).put("isDefault", true);
        send(put(crm() + "/settings"), managerToken, body).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION"));
    }

    @Test
    void rottingDaysAndCurrencyAndPipelineValidated() throws Exception {
        Map<String, Object> body = editable();
        body.put("dealRottingDays", 400);
        body.put("defaultCurrency", "ZZZ");
        body.put("defaultPipelineId", "nope");
        send(put(crm() + "/settings"), managerToken, body).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.dealRottingDays").exists())
                .andExpect(jsonPath("$.fields.defaultCurrency").exists())
                .andExpect(jsonPath("$.fields.defaultPipelineId").exists());
    }

    @Test
    void nullRottingDaysRoundTripsAsNever() throws Exception {
        Map<String, Object> body = editable();
        body.put("dealRottingDays", null);
        JsonNode v = json(send(put(crm() + "/settings"), managerToken, body).andExpect(status().isOk()));
        assertThat(v.get("dealRottingDays").isNull()).isTrue();
        assertThat(read().get("dealRottingDays").isNull()).isTrue();
        CrmSettings stored = mongo.findOne(new Query(Criteria.where("workspaceId").is(brand.getId())), CrmSettings.class);
        assertThat(stored.getDealRottingDays()).isNull();
    }
}
