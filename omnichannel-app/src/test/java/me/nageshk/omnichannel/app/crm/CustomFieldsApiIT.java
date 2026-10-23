package me.nageshk.omnichannel.app.crm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class CustomFieldsApiIT extends CrmTestBase {

    private Map<String, Object> field(String entityType, String key, String type) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("entityType", entityType);
        m.put("key", key);
        m.put("label", key.toUpperCase());
        m.put("type", type);
        return m;
    }

    private Map<String, Object> tierField() {
        Map<String, Object> m = field("DEAL", "tier", "SELECT");
        m.put("options", List.of(Map.of("key", "gold", "label", "Gold"), Map.of("key", "silver", "label", "Silver")));
        return m;
    }

    private JsonNode create(Map<String, Object> body) throws Exception {
        return json(send(post(crm() + "/fields"), managerToken, body).andExpect(status().isCreated()));
    }

    private JsonNode capturedDeal() throws Exception {
        return captured(contact("Asha", "919900000101"), number).get("deal");
    }

    private Map<String, Object> dealUpdate(JsonNode deal, Map<String, Object> customFields) {
        Map<String, Object> m = new HashMap<>();
        m.put("version", deal.get("version").asLong());
        m.put("customFields", customFields);
        return m;
    }

    @Test
    void managerCreatesDealFieldAndDealSavesValue() throws Exception {
        enableCrm();
        JsonNode f = create(tierField());
        assertThat(f.get("key").asText()).isEqualTo("tier");
        assertThat(f.get("active").asBoolean()).isTrue();
        JsonNode deal = capturedDeal();
        JsonNode saved = json(send(put(crm() + "/deals/" + deal.get("id").asText()), managerToken,
                dealUpdate(deal, Map.of("tier", "gold"))).andExpect(status().isOk()));
        assertThat(saved.get("customFields").get("tier").asText()).isEqualTo("gold");
    }

    @Test
    void contactAndCompanySaveCustomFields() throws Exception {
        enableCrm();
        create(field("CONTACT", "birthday", "DATE"));
        create(field("COMPANY", "employees", "NUMBER"));
        JsonNode contact = captured(contact("Ravi", "919900000102"), number).get("contact");
        Map<String, Object> body = new HashMap<>();
        body.put("version", contact.get("version").asLong());
        body.put("customFields", Map.of("birthday", "1990-05-01"));
        send(put(crm() + "/contacts/" + contact.get("id").asText()), managerToken, body)
                .andExpect(status().isOk()).andExpect(jsonPath("$.customFields.birthday").value("1990-05-01"));
        Map<String, Object> company = new HashMap<>(Map.of("name", "Acme Traders"));
        company.put("customFields", Map.of("employees", "42"));
        send(post(crm() + "/companies"), managerToken, company)
                .andExpect(jsonPath("$.customFields.employees").value(42));
    }

    @Test
    void limitPerEntityType() throws Exception {
        enableCrm(null, 2);
        create(field("CONTACT", "one", "TEXT"));
        create(field("CONTACT", "two", "TEXT"));
        send(post(crm() + "/fields"), managerToken, field("CONTACT", "three", "TEXT"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("LIMIT_REACHED"))
                .andExpect(jsonPath("$.limit").value("2"));
        create(field("DEAL", "one", "TEXT"));
    }

    @Test
    void keyImmutable() throws Exception {
        enableCrm();
        JsonNode f = create(field("DEAL", "tier", "TEXT"));
        Map<String, Object> body = field("DEAL", "rank", "TEXT");
        body.put("version", f.get("version").asLong());
        send(put(crm() + "/fields/" + f.get("id").asText()), managerToken, body)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("FIELD_IMMUTABLE"));
        body = field("DEAL", "tier", "NUMBER");
        body.put("version", f.get("version").asLong());
        send(put(crm() + "/fields/" + f.get("id").asText()), managerToken, body)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("FIELD_IMMUTABLE"));
        body = field("DEAL", "tier", "TEXT");
        body.put("label", "Tier level");
        body.put("version", f.get("version").asLong());
        send(put(crm() + "/fields/" + f.get("id").asText()), managerToken, body)
                .andExpect(status().isOk()).andExpect(jsonPath("$.label").value("Tier level"));
    }

    @Test
    void optionKeysMayNotDisappear() throws Exception {
        enableCrm();
        JsonNode f = create(tierField());
        Map<String, Object> body = tierField();
        body.put("options", List.of(Map.of("key", "gold", "label", "Gold")));
        body.put("version", f.get("version").asLong());
        send(put(crm() + "/fields/" + f.get("id").asText()), managerToken, body)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION"));
    }

    @Test
    void duplicateKeyRejected() throws Exception {
        enableCrm();
        create(field("DEAL", "tier", "TEXT"));
        send(post(crm() + "/fields"), managerToken, field("DEAL", "tier", "NUMBER"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("FIELD_KEY_TAKEN"));
        create(field("CONTACT", "tier", "TEXT"));
    }

    @Test
    void badKeyRejected() throws Exception {
        enableCrm();
        for (String key : List.of("Tier.$x", "a", "1st", "x".repeat(41))) {
            send(post(crm() + "/fields"), managerToken, field("DEAL", key, "TEXT"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.key").exists());
        }
    }

    @Test
    void softDeleteHidesFromDefaultList() throws Exception {
        enableCrm();
        JsonNode f = create(field("DEAL", "tier", "TEXT"));
        send(delete(crm() + "/fields/" + f.get("id").asText()), managerToken, null).andExpect(status().isNoContent());
        send(get(crm() + "/fields?entityType=DEAL"), agentToken, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        send(get(crm() + "/fields?entityType=DEAL&includeInactive=true"), managerToken, null)
                .andExpect(jsonPath("$[0].key").value("tier")).andExpect(jsonPath("$[0].active").value(false));
    }

    @Test
    void reorderPersists() throws Exception {
        enableCrm();
        JsonNode a = create(field("DEAL", "aa", "TEXT"));
        JsonNode b = create(field("DEAL", "bb", "TEXT"));
        send(put(crm() + "/fields/reorder"), managerToken,
                Map.of("entityType", "DEAL", "ids", List.of(b.get("id").asText(), a.get("id").asText())))
                .andExpect(status().isNoContent());
        JsonNode list = json(send(get(crm() + "/fields?entityType=DEAL"), managerToken, null));
        List<String> keys = new ArrayList<>();
        list.forEach(n -> keys.add(n.get("key").asText()));
        assertThat(keys).containsExactly("bb", "aa");
    }

    @Test
    void agentCannotManageFields() throws Exception {
        enableCrm();
        send(post(crm() + "/fields"), agentToken, field("DEAL", "tier", "TEXT")).andExpect(status().isForbidden());
        send(get(crm() + "/fields?entityType=DEAL"), agentToken, null).andExpect(status().isOk());
    }

    @Test
    void invalidValueGivesFieldError() throws Exception {
        enableCrm();
        create(tierField());
        JsonNode deal = capturedDeal();
        send(put(crm() + "/deals/" + deal.get("id").asText()), managerToken, dealUpdate(deal, Map.of("tier", "platinum")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION"))
                .andExpect(jsonPath("$.fields['customFields.tier']").exists());
        send(put(crm() + "/deals/" + deal.get("id").asText()), managerToken, dealUpdate(deal, Map.of("nope", "x")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields['customFields.nope']").exists());
    }

    @Test
    void captureIgnoresRequiredField() throws Exception {
        enableCrm();
        Map<String, Object> required = tierField();
        required.put("required", true);
        create(required);
        JsonNode result = captured(contact("Meena", "919900000103"), number);
        assertThat(result.get("createdDeal").asBoolean()).isTrue();
        Map<String, Object> deal = new HashMap<>();
        deal.put("title", "Second");
        deal.put("pipelineId", salesId());
        deal.put("primaryCrmContactId", result.get("contact").get("id").asText());
        send(post(crm() + "/deals"), managerToken, deal).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields['customFields.tier']").exists());
        deal.put("customFields", Map.of("tier", "silver"));
        send(post(crm() + "/deals"), managerToken, deal).andExpect(status().isOk())
                .andExpect(jsonPath("$.customFields.tier").value("silver"));
    }

    @Test
    void defaultAppliedOnNewDeal() throws Exception {
        enableCrm();
        Map<String, Object> withDefault = tierField();
        withDefault.put("defaultValue", "silver");
        create(withDefault);
        assertThat(capturedDeal().get("customFields").get("tier").asText()).isEqualTo("silver");
    }
}
