package me.nageshk.omnichannel.app.inbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Map;
import java.util.List;
import me.nageshk.omnichannel.db.user.WorkspaceRole;
import me.nageshk.omnichannel.security.access.BuiltInRoles;
import me.nageshk.omnichannel.service.cloud.CloudMediaService;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.gridfs.GridFsTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

/** Spec §5.6: the Cloud-style media upload, brand-scoped, sniffed, capped, and swept when unreferenced. */
class CloudMediaIT extends InboxApiTestBase {

    @Autowired @Qualifier("cloudMediaGridFs") GridFsTemplate files;
    @Autowired CloudMediaService media;

    static byte[] png(int size) {
        byte[] b = new byte[size];
        byte[] sig = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
        System.arraycopy(sig, 0, b, 0, sig.length);
        return b;
    }

    private String mediaUrl() {
        return "/api/workspaces/" + brand.getId() + "/channel-accounts/" + number.getId() + "/media";
    }

    private ResultActions upload(String token, String product, String type, String name, byte[] bytes) throws Exception {
        return mvc.perform(multipart(mediaUrl())
                .file(new MockMultipartFile("file", name, "application/octet-stream", bytes))
                .param("messaging_product", product).param("type", type)
                .header("Authorization", "Bearer " + token));
    }

    private String uploaded() throws Exception {
        JsonNode body = json(upload(agentToken, "whatsapp-web", "image", "a.png", png(100)).andExpect(status().isOk()));
        return body.get("id").asString();
    }

    @Test
    void uploadsAnImageAndAnswersWithItsId() throws Exception {
        String id = uploaded();
        assertThat(ObjectId.isValid(id)).isTrue();
        Document meta = files.findOne(new Query(Criteria.where("_id").is(new ObjectId(id)))).getMetadata();
        assertThat(meta.getString("workspaceId")).isEqualTo(brand.getId());
        assertThat(meta.getString("mimeType")).isEqualTo("image/png");
        assertThat(meta.getString("uploadedBy")).isEqualTo(agent.getUsername());
        assertThat(meta.getString("sha256")).hasSize(64);
        assertThat(media.mimeOf(brand.getId(), id)).contains("image/png");
        assertThat(media.mimeOf(other.getId(), id)).isEmpty();
    }

    @Test
    void rejectsWhatsappProduct() throws Exception {
        upload(agentToken, "whatsapp", "image", "a.png", png(100)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.messaging_product").value("must be whatsapp-web"));
    }

    @Test
    void rejectsWrongTypeForHeader() throws Exception {
        upload(agentToken, "whatsapp-web", "video", "a.png", png(100)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.file").value("must be an MP4 or 3GPP video"));
    }

    @Test
    void rejectsUnknownBytesAndEmptyFiles() throws Exception {
        upload(agentToken, "whatsapp-web", "image", "run.exe", new byte[] {'M', 'Z', (byte) 0x90, 0})
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.file").value("is not a supported file type"));
        upload(agentToken, "whatsapp-web", "image", "e.png", new byte[0])
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.file").value("is required"));
    }

    @Test
    void rejectsOversizedImage() throws Exception {
        upload(agentToken, "whatsapp-web", "image", "big.png", png(5 * 1024 * 1024 + 1)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.file").value("is larger than 5 MB"));
    }

    @Test
    void unknownNumberIs404() throws Exception {
        mvc.perform(multipart("/api/workspaces/" + brand.getId() + "/channel-accounts/" + otherNumber.getId() + "/media")
                .file(new MockMultipartFile("file", "a.png", "application/octet-stream", png(100)))
                .param("messaging_product", "whatsapp-web").param("type", "image")
                .header("Authorization", "Bearer " + agentToken)).andExpect(status().isNotFound());
    }

    @Test
    void infoAndFileAreBrandScoped() throws Exception {
        String id = uploaded();
        mvc.perform(get(mediaUrl() + "/" + id).header("Authorization", "Bearer " + agentToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messaging_product").value("whatsapp-web"))
                .andExpect(jsonPath("$.mime_type").value("image/png"))
                .andExpect(jsonPath("$.file_size").value(100))
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.url").value("http://pages.test" + mediaUrl() + "/" + id + "/file"));
        mvc.perform(get(mediaUrl() + "/" + id + "/file").header("Authorization", "Bearer " + agentToken))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(content().bytes(png(100)));
        String otherUrl = "/api/workspaces/" + other.getId() + "/channel-accounts/" + otherNumber.getId() + "/media/" + id;
        String otherToken = testUsers.accessToken(testUsers.createInCustomer(acme.getId(), "wmb", null,
                List.of(new WorkspaceRole(other.getId(), testUsers.roleId(BuiltInRoles.WORKSPACE_MANAGER)))));
        mvc.perform(get(otherUrl).header("Authorization", "Bearer " + otherToken)).andExpect(status().isNotFound());
        mvc.perform(get(otherUrl + "/file").header("Authorization", "Bearer " + otherToken)).andExpect(status().isNotFound());
    }

    @Test
    void sweepKeepsReferencedFiles() throws Exception {
        String kept = uploaded();
        String orphan = uploaded();
        mongo.insert(new Document(Map.of("workspaceId", brand.getId(), "token", "t1",
                "message", new Document("interactive", new Document("header",
                        new Document("image", new Document("id", kept)))))), "messagePages");
        int deleted = media.sweepOrphans(Instant.now().plusSeconds(3600));
        assertThat(deleted).isEqualTo(1);
        assertThat(media.mimeOf(brand.getId(), kept)).isPresent();
        assertThat(media.mimeOf(brand.getId(), orphan)).isEmpty();
    }
}
