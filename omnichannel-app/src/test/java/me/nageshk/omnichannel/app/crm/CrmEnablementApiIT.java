package me.nageshk.omnichannel.app.crm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.Map;
import me.nageshk.omnichannel.db.enablement.EnablementChange;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import tools.jackson.databind.JsonNode;

class CrmEnablementApiIT extends CrmTestBase {

    private String url(String customerId, String workspaceId) {
        return "/api/customers/" + customerId + "/workspaces/" + workspaceId + "/crm";
    }

    private String url() { return url(acme.getId(), brand.getId()); }

    private Map<String, Object> body(Boolean enabled, Integer pipelines, Integer fields) {
        Map<String, Object> m = new HashMap<>();
        m.put("enabled", enabled);
        m.put("maxPipelines", pipelines);
        m.put("maxCustomFields", fields);
        return m;
    }

    private long crmChanges() {
        return mongo.count(new Query(Criteria.where("targetType").is("crm").and("targetId").is(brand.getId())),
                EnablementChange.class);
    }

    @Test
    void staffSwitchesCrmOnWithLimits() throws Exception {
        JsonNode v = json(send(put(url()), staffToken, body(true, 3, 20)).andExpect(status().isOk()));
        assertThat(v.get("enabled").asBoolean()).isTrue();
        assertThat(v.get("maxPipelines").asInt()).isEqualTo(3);
        assertThat(v.get("maxCustomFields").asInt()).isEqualTo(20);
        me.nageshk.omnichannel.crm.settings.CrmSettings stored = mongo.findOne(
                new Query(Criteria.where("workspaceId").is(brand.getId())),
                me.nageshk.omnichannel.crm.settings.CrmSettings.class);
        assertThat(stored.getCreatedBy()).isEqualTo(staff.getId());
        assertThat(stored.getLastModifiedBy()).isEqualTo(staff.getId());
        JsonNode g = json(send(get(url()), staffToken, null).andExpect(status().isOk()));
        assertThat(g.get("enabled").asBoolean()).isTrue();
        assertThat(g.get("maxPipelines").asInt()).isEqualTo(3);
    }

    @Test
    void getReturnsOffDefaultsWhenNeverSet() throws Exception {
        JsonNode g = json(send(get(url()), staffToken, null).andExpect(status().isOk()));
        assertThat(g.get("enabled").asBoolean()).isFalse();
        assertThat(g.get("maxPipelines").isNull()).isTrue();
        assertThat(g.get("maxCustomFields").isNull()).isTrue();
    }

    @Test
    void brandOfAnotherCustomerIs404() throws Exception {
        Object otherCustomer = testUsers.customer("Other Co").getId();
        send(put(url((String) otherCustomer, brand.getId())), staffToken, body(true, null, null))
                .andExpect(status().isNotFound());
        send(get(url((String) otherCustomer, brand.getId())), staffToken, null).andExpect(status().isNotFound());
    }

    @Test
    void brandUserGets403() throws Exception {
        send(put(url()), managerToken, body(true, null, null)).andExpect(status().isForbidden());
        send(get(url()), managerToken, null).andExpect(status().isForbidden());
    }

    @Test
    void switchChangeIsAudited() throws Exception {
        send(put(url()), staffToken, body(true, null, null)).andExpect(status().isOk());
        assertThat(crmChanges()).isEqualTo(1);
        send(put(url()), staffToken, body(true, 5, null)).andExpect(status().isOk());
        assertThat(crmChanges()).isEqualTo(1);
        send(put(url()), staffToken, body(false, 5, null)).andExpect(status().isOk());
        assertThat(crmChanges()).isEqualTo(2);
    }

    @Test
    void limitsBelowOneRejected() throws Exception {
        JsonNode e = json(send(put(url()), staffToken, body(true, 0, null)).andExpect(status().isBadRequest()));
        assertThat(e.get("code").asString()).isEqualTo("VALIDATION");
        assertThat(e.get("fields").has("maxPipelines")).isTrue();
        JsonNode e2 = json(send(put(url()), staffToken, body(true, null, -1)).andExpect(status().isBadRequest()));
        assertThat(e2.get("fields").has("maxCustomFields")).isTrue();
        JsonNode e3 = json(send(put(url()), staffToken, body(null, null, null)).andExpect(status().isBadRequest()));
        assertThat(e3.get("fields").has("enabled")).isTrue();
    }
}
