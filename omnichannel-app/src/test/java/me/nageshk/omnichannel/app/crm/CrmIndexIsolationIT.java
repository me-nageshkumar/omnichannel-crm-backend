package me.nageshk.omnichannel.app.crm;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
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
        // phase B: tasks, a note with an attachment, an export and an import
        Map<String, Object> target = Map.of("type", "DEAL", "id", deal.get("id").asText());
        send(MockMvcRequestBuilders.post(crm() + "/tasks"), managerToken, Map.of("title", "Call back", "target",
                target, "dueAt", Instant.now().plusSeconds(3600).toString()))
                .andExpect(MockMvcResultMatchers.status().isOk());
        JsonNode note = json(send(MockMvcRequestBuilders.post(crm() + "/notes"), managerToken,
                Map.of("target", target, "body", "Sizes agreed")).andExpect(MockMvcResultMatchers.status().isOk()));
        mvc.perform(MockMvcRequestBuilders.multipart(crm() + "/notes/" + note.get("id").asText() + "/attachments")
                .file(new MockMultipartFile("file", "sizes.txt", "text/plain", "S,M,L".getBytes()))
                .header("Authorization", "Bearer " + managerToken)).andExpect(MockMvcResultMatchers.status().isOk());
        mvc.perform(MockMvcRequestBuilders.asyncDispatch(send(MockMvcRequestBuilders.post(crm() + "/deals/export"),
                managerToken, Map.of()).andReturn())).andExpect(MockMvcResultMatchers.status().isOk());
        mvc.perform(MockMvcRequestBuilders.multipart(crm() + "/contacts/import").file(new MockMultipartFile("file",
                "c.csv", "text/csv", "WhatsApp number,First name\n919800000002,Ravi\n".getBytes()))
                .param("pipelineId", salesId()).param("phoneNumberId", number.getId())
                .header("Authorization", "Bearer " + managerToken))
                .andExpect(MockMvcResultMatchers.jsonPath("$.created").value(1));

        assertThat(snapshot()).isEqualTo(before);

        List<String> crmCollections = mongo.getCollectionNames().stream().filter(n -> n.startsWith("crm")).toList();
        assertThat(crmCollections).contains("crmContacts", "crmDeals", "crmActivities", "crmTasks", "crmNotes",
                "crmNoteFiles.files", "crmNoteFiles.chunks");
        for (String name : crmCollections) {
            // GridFS buckets carry the driver's own indexes; their queries filter on metadata.workspaceId
            if (name.endsWith(".files") || name.endsWith(".chunks")) continue;
            for (Document index : mongo.getCollection(name).listIndexes()) {
                if ("_id_".equals(index.getString("name"))) continue;
                Document key = (Document) index.get("key");
                assertThat(key.keySet().iterator().next()).as(name + "." + index.getString("name"))
                        .isEqualTo("workspaceId");
            }
        }
    }
}
