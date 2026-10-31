package me.nageshk.omnichannel.app.inbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class HistoryImportApiIT extends InboxApiTestBase {

    private String numberUrl() {
        return inbox() + "/phone-numbers/" + number.getId() + "/history-imports";
    }

    private JsonNode createAll() throws Exception {
        return json(send(post(numberUrl()), managerToken, Map.of("scope", Map.of("kind", "ALL")))
                .andExpect(status().isCreated()));
    }

    @Test
    void managerCreatesAllImport() throws Exception {
        JsonNode v = createAll();
        assertThat(v.get("status").asString()).isEqualTo("QUEUED");
        assertThat(v.get("scope").get("kind").asString()).isEqualTo("ALL");
        assertThat(v.get("phoneNumber").get("number").asString()).isEqualTo(number.getNumber());
        assertThat(v.get("progressPercent").isNull()).isTrue();
    }

    @Test
    void identicalRequestReturns200WithSameId() throws Exception {
        String id = createAll().get("id").asString();
        JsonNode again = json(send(post(numberUrl()), managerToken, Map.of("scope", Map.of("kind", "ALL")))
                .andExpect(status().isOk()));
        assertThat(again.get("id").asString()).isEqualTo(id);
    }

    @Test
    void agentIsForbidden() throws Exception {
        send(post(numberUrl()), agentToken, Map.of("scope", Map.of("kind", "ALL"))).andExpect(status().isForbidden());
        send(get(inbox() + "/history-imports"), agentToken, null).andExpect(status().isForbidden());
    }

    @Test
    void otherWorkspaceNumberIs404() throws Exception {
        send(post(inbox() + "/phone-numbers/" + otherNumber.getId() + "/history-imports"), managerToken,
                Map.of("scope", Map.of("kind", "ALL"))).andExpect(status().isNotFound());
    }

    @Test
    void chatImportUsesRemoteId() throws Exception {
        var c = chat("Asha", "919800000001", 1);
        JsonNode v = json(send(post(inbox() + "/chats/" + c.getId() + "/history-imports"), managerToken, Map.of())
                .andExpect(status().isCreated()));
        JsonNode page = json(send(get(inbox() + "/history-imports/" + v.get("id").asString() + "/chats"),
                managerToken, null).andExpect(status().isOk()));
        assertThat(page.get("items")).hasSize(1);
        assertThat(page.get("items").get(0).get("remoteId").asString()).isEqualTo(c.getRemoteId());
    }

    @Test
    void invalidRangeIs400Validation() throws Exception {
        send(post(numberUrl()), managerToken, Map.of("scope", Map.of("kind", "ALL"),
                "from", "2026-10-02T00:00:00Z", "to", "2026-10-01T00:00:00Z"))
                .andExpect(status().isBadRequest())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.code").value("VALIDATION"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.fields.from").exists());
    }

    @Test
    void missingScopeIs400Validation() throws Exception {
        send(post(numberUrl()), managerToken, Map.of())
                .andExpect(status().isBadRequest())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.code").value("VALIDATION"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.fields.scope").exists());
    }

    @Test
    void listFiltersByStatus() throws Exception {
        createAll();
        JsonNode queued = json(send(get(inbox() + "/history-imports?status=QUEUED&phoneNumberId=" + number.getId()),
                managerToken, null).andExpect(status().isOk()));
        assertThat(queued.get("total").asLong()).isEqualTo(1);
        JsonNode done = json(send(get(inbox() + "/history-imports?status=COMPLETED"), managerToken, null)
                .andExpect(status().isOk()));
        assertThat(done.get("items")).isEmpty();
    }

    @Test
    void cancelQueued() throws Exception {
        String id = createAll().get("id").asString();
        JsonNode v = json(send(post(inbox() + "/history-imports/" + id + "/cancel"), managerToken, null)
                .andExpect(status().isOk()));
        assertThat(v.get("status").asString()).isEqualTo("CANCELLED");
    }

    @Test
    void cancelAgainIs409() throws Exception {
        String id = createAll().get("id").asString();
        send(post(inbox() + "/history-imports/" + id + "/cancel"), managerToken, null).andExpect(status().isOk());
        send(post(inbox() + "/history-imports/" + id + "/cancel"), managerToken, null)
                .andExpect(status().isConflict())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.code").value("HISTORY_IMPORT_STATE"));
    }

    @Test
    void unknownIdIs404() throws Exception {
        send(get(inbox() + "/history-imports/nope"), managerToken, null).andExpect(status().isNotFound());
    }
}
