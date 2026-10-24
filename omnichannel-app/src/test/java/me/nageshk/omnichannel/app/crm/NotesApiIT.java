package me.nageshk.omnichannel.app.crm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import me.nageshk.omnichannel.crm.activity.ActivityType;
import me.nageshk.omnichannel.crm.activity.CrmActivity;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

class NotesApiIT extends CrmTestBase {

    private String dealId;

    @BeforeEach
    void deal() throws Exception {
        enableCrm();
        dealId = captured(contact("Asha", "919900000401"), number).get("deal").get("id").asText();
    }

    private Map<String, Object> note(String body, Boolean pinned) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("target", Map.of("type", "DEAL", "id", dealId));
        m.put("body", body);
        if (pinned != null) m.put("pinned", pinned);
        return m;
    }

    private JsonNode create(String token, String body, Boolean pinned) throws Exception {
        return json(send(post(crm() + "/notes"), token, note(body, pinned)).andExpect(status().isOk()));
    }

    private ResultActions upload(String token, String noteId, String name, String type, byte[] bytes) throws Exception {
        return mvc.perform(multipart(crm() + "/notes/" + noteId + "/attachments")
                .file(new MockMultipartFile("file", name, type, bytes)).header("Authorization", "Bearer " + token));
    }

    private long files() {
        return mongo.getCollection("crmNoteFiles.files").countDocuments();
    }

    @Test
    void createListPinnedFirst() throws Exception {
        create(managerToken, "first", null);
        create(managerToken, "pinned", true);
        create(managerToken, "latest", null);
        JsonNode page = json(send(get(crm() + "/notes?targetType=DEAL&targetId=" + dealId), managerToken, null)
                .andExpect(status().isOk()));
        assertThat(page.get("total").asInt()).isEqualTo(3);
        assertThat(page.get("items").get(0).get("body").asText()).isEqualTo("pinned");
        assertThat(page.get("items").get(1).get("body").asText()).isEqualTo("latest");
        assertThat(page.get("items").get(0).get("author").get("id").asText()).isEqualTo(manager.getId());
        CrmActivity a = mongo.findOne(new Query(Criteria.where("type").is(ActivityType.NOTE_ADDED)), CrmActivity.class);
        assertThat(a.getPayload()).containsEntry("attachmentCount", 0);
    }

    @Test
    void pinMovesNoteUp() throws Exception {
        create(managerToken, "old", null);
        JsonNode n = create(managerToken, "new", null);
        JsonNode first = create(managerToken, "newest", null);
        send(post(crm() + "/notes/" + n.get("id").asText() + "/pin"), managerToken, Map.of("pinned", true))
                .andExpect(status().isOk()).andExpect(jsonPath("$.pinned").value(true));
        send(get(crm() + "/notes?targetType=DEAL&targetId=" + dealId), managerToken, null)
                .andExpect(jsonPath("$.items[0].body").value("new"))
                .andExpect(jsonPath("$.items[1].body").value(first.get("body").asText()));
    }

    @Test
    void emptyOrTooLongBodyRejected() throws Exception {
        send(post(crm() + "/notes"), managerToken, note("  ", null)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.body").exists());
        send(post(crm() + "/notes"), managerToken, note("x".repeat(20_001), null)).andExpect(status().isBadRequest());
    }

    @Test
    void noteOnInaccessibleDealIs404() throws Exception {
        JsonNode n = create(managerToken, "secret", null);
        send(get(crm() + "/notes?targetType=DEAL&targetId=" + dealId), agentToken, null).andExpect(status().isNotFound());
        send(post(crm() + "/notes"), agentToken, note("x", null)).andExpect(status().isNotFound());
        upload(managerToken, n.get("id").asText(), "a.txt", "text/plain", "hi".getBytes()).andExpect(status().isOk());
        String att = json(send(get(crm() + "/notes?targetType=DEAL&targetId=" + dealId), managerToken, null))
                .get("items").get(0).get("attachments").get(0).get("id").asText();
        send(get(crm() + "/notes/" + n.get("id").asText() + "/attachments/" + att), agentToken, null)
                .andExpect(status().isNotFound());
    }

    @Test
    void uploadDownloadRoundTrip() throws Exception {
        JsonNode n = create(managerToken, "with file", null);
        byte[] bytes = "%PDF-1.4 hello".getBytes(StandardCharsets.UTF_8);
        JsonNode withFile = json(upload(managerToken, n.get("id").asText(), "../../etc/Quote é.pdf",
                "application/pdf", bytes).andExpect(status().isOk()));
        JsonNode att = withFile.get("attachments").get(0);
        assertThat(att.get("fileName").asText()).isEqualTo("Quote é.pdf");
        assertThat(att.get("size").asLong()).isEqualTo(bytes.length);
        byte[] back = send(get(crm() + "/notes/" + n.get("id").asText() + "/attachments/" + att.get("id").asText()),
                managerToken, null).andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("filename*=UTF-8''Quote%20%C3%A9.pdf")))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(back).isEqualTo(bytes);
        send(delete(crm() + "/notes/" + n.get("id").asText() + "/attachments/" + att.get("id").asText()), managerToken,
                null).andExpect(status().isOk()).andExpect(jsonPath("$.attachments.length()").value(0));
        assertThat(files()).isZero();
    }

    @Test
    void sixthAttachmentRejected() throws Exception {
        String id = create(managerToken, "many", null).get("id").asText();
        for (int i = 0; i < 5; i++) {
            upload(managerToken, id, "f" + i + ".txt", "text/plain", ("x" + i).getBytes()).andExpect(status().isOk());
        }
        upload(managerToken, id, "f6.txt", "text/plain", "x".getBytes()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ATTACHMENT_REJECTED"));
    }

    @Test
    void executableRejected() throws Exception {
        String id = create(managerToken, "exe", null).get("id").asText();
        upload(managerToken, id, "run.exe", "application/x-msdownload", new byte[] {1, 2}).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ATTACHMENT_REJECTED"));
        upload(managerToken, id, "photo.png", "image/png", new byte[] {1, 2}).andExpect(status().isOk());
    }

    @Test
    void disguisedOrScriptableFilesRejected() throws Exception {
        String id = create(managerToken, "files", null).get("id").asText();
        for (String[] f : new String[][] {{"evil.exe", "image/png"}, {"logo.svg", "image/svg+xml"}, {"x.png", "image/"},
                {"noext", "application/pdf"}, {"photo.png", "application/pdf"}}) {
            upload(managerToken, id, f[0], f[1], new byte[] {1, 2}).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("ATTACHMENT_REJECTED"));
        }
        assertThat(files()).isZero();
        upload(managerToken, id, "notes.txt", "text/plain;charset=UTF-8", new byte[] {65}).andExpect(status().isOk());
        JsonNode n = json(upload(managerToken, id, "Report.PDF", "application/octet-stream", new byte[] {37, 80})
                .andExpect(status().isOk()));
        assertThat(n.get("attachments").get(1).get("contentType").asText()).isEqualTo("application/pdf");
        assertThat(n.get("attachments").get(0).get("contentType").asText()).isEqualTo("text/plain");
    }

    @Test
    void oversizeRejected() throws Exception {
        String id = create(managerToken, "big", null).get("id").asText();
        upload(managerToken, id, "big.pdf", "application/pdf", new byte[10 * 1024 * 1024 + 1])
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("ATTACHMENT_REJECTED"));
        assertThat(files()).isZero();
    }

    @Test
    void deletingNoteRemovesFiles() throws Exception {
        String id = create(managerToken, "bye", null).get("id").asText();
        upload(managerToken, id, "a.txt", "text/plain", "a".getBytes()).andExpect(status().isOk());
        assertThat(files()).isEqualTo(1);
        send(delete(crm() + "/notes/" + id), managerToken, null).andExpect(status().isNoContent());
        assertThat(files()).isZero();
        assertThat(mongo.count(new Query(), "crmNotes")).isZero();
    }

    @Test
    void deletingDealRemovesItsNotesAndFiles() throws Exception {
        String id = create(managerToken, "on deal", null).get("id").asText();
        upload(managerToken, id, "a.txt", "text/plain", "a".getBytes()).andExpect(status().isOk());
        send(delete(crm() + "/deals/" + dealId), managerToken, null).andExpect(status().isNoContent());
        assertThat(mongo.count(new Query(), "crmNotes")).isZero();
        assertThat(files()).isZero();
    }

    @Test
    void onlyAuthorOrDeleterEdits() throws Exception {
        assignPipelines(agent, salesId());
        JsonNode n = create(agentToken, "agent's", null);
        Map<String, Object> edit = note("changed", null);
        edit.put("version", n.get("version").asLong());
        send(put(crm() + "/notes/" + n.get("id").asText()), managerToken, edit).andExpect(status().isOk())
                .andExpect(jsonPath("$.body").value("changed"));
        JsonNode mine = create(managerToken, "manager's", null);
        Map<String, Object> other = note("hijack", null);
        other.put("version", mine.get("version").asLong());
        send(put(crm() + "/notes/" + mine.get("id").asText()), agentToken, other).andExpect(status().isForbidden());
        send(delete(crm() + "/notes/" + mine.get("id").asText()), agentToken, null).andExpect(status().isForbidden());
        Document stored = mongo.findOne(new Query(Criteria.where("_id").is(mine.get("id").asText())), Document.class,
                "crmNotes");
        assertThat(stored.getString("body")).isEqualTo("manager's");
    }
}
