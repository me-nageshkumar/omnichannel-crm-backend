package me.nageshk.omnichannel.app.crm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;
import tools.jackson.databind.JsonNode;

/** The CRM adds indexes only to its own {@code crm*} collections, and every one of them leads with workspaceId. */
class CrmIndexIsolationIT extends CrmTestBase {

    private Map<String, List<String>> snapshot() {
        Map<String, List<String>> out = new TreeMap<>();
        for (String name : mongo.getCollectionNames()) {
            if (name.startsWith("crm") || name.startsWith("system.")) continue;
            out.put(name, mongo.getCollection(name).listIndexes().into(new java.util.ArrayList<>()).stream()
                    .map(d -> d.getString("name") + " " + ((Document) d.get("key")).toJson()).sorted().toList());
        }
        return out;
    }

    @Test
    void crmCreatesIndexesOnlyOnItsOwnCollections() throws Exception {
        Map<String, List<String>> before = snapshot();

        enableCrm();
        JsonNode captured = captured(contact("Asha", "919800000001"), number);
        String contactId = captured.at("/contact/id").asText();
        Map<String, String> stages = stages(salesId());
        JsonNode deal = createDeal(Map.of("title", "Order", "pipelineId", salesId(),
                "primaryCrmContactId", contactId));
        send(MockMvcRequestBuilders.post(crm() + "/deals/" + deal.get("id").asText() + "/move"), managerToken,
                Map.of("stageId", stages.get("Qualified"))).andExpect(MockMvcResultMatchers.status().isOk());
        send(MockMvcRequestBuilders.post(crm() + "/activities/log"), managerToken,
                Map.of("target", Map.of("type", "DEAL", "id", deal.get("id").asText()), "type", "CALL",
                        "summary", "Called")).andExpect(MockMvcResultMatchers.status().isOk());

        assertThat(snapshot()).isEqualTo(before);

        List<String> crmCollections = mongo.getCollectionNames().stream().filter(n -> n.startsWith("crm")).toList();
        assertThat(crmCollections).contains("crmContacts", "crmDeals", "crmActivities");
        for (String name : crmCollections) {
            for (Document index : mongo.getCollection(name).listIndexes()) {
                if ("_id_".equals(index.getString("name"))) continue;
                Document key = (Document) index.get("key");
                assertThat(key.keySet().iterator().next()).as(name + "." + index.getString("name"))
                        .isEqualTo("workspaceId");
            }
        }
    }
}
