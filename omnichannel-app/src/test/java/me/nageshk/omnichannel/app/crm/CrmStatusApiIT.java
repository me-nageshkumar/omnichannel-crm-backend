package me.nageshk.omnichannel.app.crm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class CrmStatusApiIT extends CrmTestBase {

    @Test
    void statusWhenOffSaysDisabled() throws Exception {
        send(get(crm() + "/status"), managerToken, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));
    }

    @Test
    void statusForAgentListsNumbersWithCanChat() throws Exception {
        enableCrm(3, null);
        JsonNode v = json(send(get(crm() + "/status"), agentToken, null).andExpect(status().isOk()));
        assertThat(v.get("enabled").asBoolean()).isTrue();
        assertThat(v.get("limits").get("maxPipelines").asInt()).isEqualTo(3);
        assertThat(v.get("limits").get("maxCustomFields").isNull()).isTrue();
        assertThat(v.get("attachmentsEnabled").asBoolean()).isTrue();
        assertThat(v.get("pipelineAccess").isArray()).isTrue();
        assertThat(v.get("pipelineAccess")).isEmpty();
        boolean c1 = false, c2 = false;
        for (JsonNode p : v.get("phones")) {
            if (p.get("phoneNumberId").asText().equals(number.getId())) c1 = p.get("canChat").asBoolean();
            if (p.get("phoneNumberId").asText().equals(number2.getId())) c2 = p.get("canChat").asBoolean();
        }
        assertThat(c1).isTrue();
        assertThat(c2).isFalse();
        assertThat(v.get("phones")).hasSize(2);
        assertThat(v.get("authorities")).extracting(JsonNode::asText).isSorted().contains("crm:view");
    }

    @Test
    void statusForManagerSaysAll() throws Exception {
        enableCrm();
        send(get(crm() + "/status"), managerToken, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.pipelineAccess").value("ALL"))
                .andExpect(jsonPath("$.authorities[?(@ == 'crm:view-all')]").isNotEmpty());
    }

    @Test
    void everyEndpointButStatusAnswers402WhenOff() throws Exception {
        send(get(crm() + "/settings"), managerToken, null).andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.code").value("CRM_NOT_ENABLED"));
        send(put(crm() + "/settings"), managerToken, Map.of("version", 0)).andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.code").value("CRM_NOT_ENABLED"));
        send(get(crm() + "/status"), managerToken, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));
    }

    @Test
    void otherBrandStatusIs404() throws Exception {
        enableCrm();
        send(get("/api/workspaces/" + other.getId() + "/crm/status"), agentToken, null)
                .andExpect(status().isNotFound());
    }
}
