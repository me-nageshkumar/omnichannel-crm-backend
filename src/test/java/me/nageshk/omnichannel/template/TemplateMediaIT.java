package me.nageshk.omnichannel.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import me.nageshk.omnichannel.access.AccessService;
import me.nageshk.omnichannel.api.NotFoundException;
import me.nageshk.omnichannel.api.ValidationException;
import me.nageshk.omnichannel.auth.AuthenticatedUser;
import me.nageshk.omnichannel.user.UserRepository;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationContext;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.gridfs.GridFsTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

class TemplateMediaIT extends TemplateApiTestBase {

    @Autowired TemplateMediaService media;
    @Autowired @Qualifier("templateMediaGridFs") GridFsTemplate gridFs;
    @Autowired AccessService access;
    @Autowired UserRepository users;
    @Autowired ApplicationContext context;

    private static byte[] png() {
        byte[] b = new byte[40];
        byte[] sig = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
        System.arraycopy(sig, 0, b, 0, sig.length);
        b[20] = 7;
        return b;
    }

    private static byte[] pdf() {
        return "%PDF-1.7\n1 0 obj\n".getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] ogg(String codec) {
        byte[] b = new byte[64];
        byte[] head = ("OggS" + "\0".repeat(24) + codec).getBytes(StandardCharsets.ISO_8859_1);
        System.arraycopy(head, 0, b, 0, head.length);
        return b;
    }

    private static byte[] mp3() {
        return new byte[] {(byte) 0xFF, (byte) 0xFB, (byte) 0x90, 0, 0, 0, 0, 0};
    }

    private String mediaUrl() {
        return base(brandA) + "/template-media";
    }

    private ResultActions upload(String token, String ws, String fileName, byte[] bytes) throws Exception {
        return mvc.perform(multipart("/api/workspaces/" + ws + "/template-media")
                .file(new MockMultipartFile("file", fileName, "application/octet-stream", bytes))
                .header("Authorization", "Bearer " + token));
    }

    private String uploaded(String token, String ws, String fileName, byte[] bytes) throws Exception {
        return json(upload(token, ws, fileName, bytes).andExpect(status().isCreated())).get("mediaId").asString();
    }

    private static Map<String, Object> request(String name, String type, Map<String, Object> content, String mediaId) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("name", name);
        r.put("type", type);
        r.put("content", content);
        if (mediaId != null) r.put("mediaId", mediaId);
        r.put("placeholders", new ArrayList<>());
        return r;
    }

    private static Map<String, Object> caption(String text) {
        return new LinkedHashMap<>(Map.of("caption", text));
    }

    private JsonNode createImage(String name, String mediaId) throws Exception {
        return json(send(post(url()), leadToken, request(name, "IMAGE", caption("hi"), mediaId))
                .andExpect(status().isCreated()));
    }

    private boolean exists(String mediaId) {
        return gridFs.findOne(new Query(Criteria.where("_id").is(new ObjectId(mediaId)))) != null;
    }

    private AuthenticatedUser lead() {
        return access.load(users.findByUsername("lead").orElseThrow());
    }

    @Test
    void uploadAndDownload() throws Exception {
        byte[] bytes = png();
        JsonNode v = json(upload(leadToken, brandA.getId(), "logo.png", bytes).andExpect(status().isCreated()));
        assertThat(v.get("mediaId").asString()).isNotBlank();
        assertThat(v.get("fileName").asString()).isEqualTo("logo.png");
        assertThat(v.get("mimeType").asString()).isEqualTo("image/png");
        assertThat(v.get("size").asLong()).isEqualTo(bytes.length);

        byte[] got = send(get(mediaUrl() + "/" + v.get("mediaId").asString()), agentToken, null)
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andExpect(header().string("Content-Disposition", startsWith("inline; filename=\"logo.png\"")))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(got).isEqualTo(bytes);

        assertThat(gridFs.findOne(new Query())).isNotNull();
        Document meta = gridFs.findOne(new Query()).getMetadata();
        assertThat(meta.getString("workspaceId")).isEqualTo(brandA.getId());
        assertThat(meta.getString("fileName")).isEqualTo("logo.png");
        assertThat(meta.getString("mimeType")).isEqualTo("image/png");
        assertThat(meta.getBoolean("opus")).isFalse();
        assertThat(meta.getString("uploadedBy")).isEqualTo("lead");
        assertThat(meta.getDate("uploadedAt")).isNotNull();
    }

    @Test
    void downloadEncodesUnusualNames() throws Exception {
        String id = uploaded(leadToken, brandA.getId(), "../my \"résumé\".pdf", pdf());
        String cd = send(get(mediaUrl() + "/" + id), leadToken, null).andExpect(status().isOk())
                .andReturn().getResponse().getHeader("Content-Disposition");
        assertThat(cd).startsWith("inline; ").contains("filename*=UTF-8''my%20%22r%C3%A9sum%C3%A9%22.pdf").doesNotContain("..")
                .doesNotContain("/");
    }

    @Test
    void uploadOver16MbIs400() throws Exception {
        byte[] big = new byte[16 * 1024 * 1024 + 1];
        System.arraycopy(pdf(), 0, big, 0, pdf().length);
        upload(leadToken, brandA.getId(), "big.pdf", big).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION"))
                .andExpect(jsonPath("$.fields.file").exists());
        assertThatThrownBy(() -> media.upload(lead(), brandA.getId(),
                new MockMultipartFile("file", "big.pdf", "application/pdf", big)))
                .isInstanceOf(ValidationException.class);
        assertThat(gridFs.findOne(new Query())).isNull();
    }

    @Test
    void unsupportedFileIs400() throws Exception {
        upload(leadToken, brandA.getId(), "run.exe", new byte[] {'M', 'Z', (byte) 0x90, 0}).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.file").value("is not a supported file type"));
        upload(leadToken, brandA.getId(), "empty.txt", new byte[0]).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.file").exists());
        mvc.perform(multipart(mediaUrl()).header("Authorization", "Bearer " + leadToken))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.file").exists());
    }

    @Test
    void pngNamedPdfIsDetectedAsPng() throws Exception {
        JsonNode v = json(upload(leadToken, brandA.getId(), "sneaky.pdf", png()).andExpect(status().isCreated()));
        assertThat(v.get("mimeType").asString()).isEqualTo("image/png");
        assertThat(v.get("fileName").asString()).isEqualTo("sneaky.pdf");
        send(post(url()), leadToken, request("Doc", "DOCUMENT", caption("x"), v.get("mediaId").asString()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.mediaId").exists());
        createImage("Img", v.get("mediaId").asString());
    }

    @Test
    void imageTemplateWithPdfIs400() throws Exception {
        String id = uploaded(leadToken, brandA.getId(), "a.pdf", pdf());
        send(post(url()), leadToken, request("Img", "IMAGE", caption("x"), id)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.mediaId").value("a PDF can't be used for an image template"));
        send(post(url()), leadToken, request("Doc", "DOCUMENT", caption("x"), id)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.media.mimeType").value("application/pdf"));
    }

    @Test
    void mediaIsRequiredForMediaTypesAndUnknownIdsAreRejected() throws Exception {
        send(post(url()), leadToken, request("Img", "IMAGE", caption("x"), null)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.mediaId").value("is required"));
        send(post(url()), leadToken, request("Img", "IMAGE", caption("x"), "nope")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.mediaId").value("not found"));
        send(post(url()), leadToken, request("Img", "IMAGE", caption("x"), "665f1c2e9b1e8a0001a1b2c3"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.mediaId").value("not found"));
    }

    @Test
    void otherBrandMediaRejected() throws Exception {
        String id = uploaded(wmBToken, brandB.getId(), "b.png", png());
        send(post(url()), leadToken, request("Img", "IMAGE", caption("x"), id)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.mediaId").value("not found"));
        send(get(mediaUrl() + "/" + id), leadToken, null).andExpect(status().isNotFound());
        assertThatThrownBy(() -> media.open(brandA.getId(), id)).isInstanceOf(NotFoundException.class);
        assertThat(media.open(brandB.getId(), id).size()).isEqualTo(png().length);
    }

    @Test
    void sharedMediaRejected() throws Exception {
        String id = uploaded(leadToken, brandA.getId(), "a.png", png());
        createImage("First", id);
        send(post(url()), leadToken, request("Second", "IMAGE", caption("x"), id)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.mediaId").value("is used by another template"));
    }

    @Test
    void savingATemplateAgainWithItsOwnMediaIsFine() throws Exception {
        String id = uploaded(leadToken, brandA.getId(), "a.png", png());
        JsonNode t = createImage("First", id);
        Map<String, Object> req = request("First", "IMAGE", caption("new caption"), id);
        req.put("version", t.get("version").asLong());
        send(put(url() + "/" + t.get("id").asString()), leadToken, req).andExpect(status().isOk())
                .andExpect(jsonPath("$.media.mediaId").value(id))
                .andExpect(jsonPath("$.content.caption").value("new caption"));
        assertThat(exists(id)).isTrue();
    }

    @Test
    void voiceNoteOnlyForOpus() throws Exception {
        String opus = uploaded(leadToken, brandA.getId(), "v.ogg", ogg("OpusHead"));
        String vorbis = uploaded(leadToken, brandA.getId(), "w.ogg", ogg("\u0001vorbis"));
        String mp3 = uploaded(leadToken, brandA.getId(), "m.mp3", mp3());
        Map<String, Object> voice = new LinkedHashMap<>(Map.of("voiceNote", true));

        send(post(url()), leadToken, request("Voice", "AUDIO", voice, opus)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.content.voiceNote").value(true))
                .andExpect(jsonPath("$.media.mimeType").value("audio/ogg"));
        send(post(url()), leadToken, request("Vorbis", "AUDIO", voice, vorbis)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields['content.voiceNote']").exists());
        send(post(url()), leadToken, request("Mp3 voice", "AUDIO", voice, mp3)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields['content.voiceNote']").exists());
        send(post(url()), leadToken, request("Mp3", "AUDIO", new LinkedHashMap<>(), mp3)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.content.voiceNote").value(false))
                .andExpect(jsonPath("$.content.caption").doesNotExist());
    }

    @Test
    void captionsAllowPlaceholdersAndAreLimited() throws Exception {
        String id = uploaded(leadToken, brandA.getId(), "a.png", png());
        Map<String, Object> req = request("Img", "IMAGE", caption("Hi {{ contact.name }}"), id);
        req.put("placeholders", new ArrayList<>(List.of(setting("contact.name", "there", null, null, null))));
        send(post(url()), leadToken, req).andExpect(status().isCreated())
                .andExpect(jsonPath("$.content.caption").value("Hi {{contact.name}}"))
                .andExpect(jsonPath("$.placeholders[0].name").value("contact.name"))
                .andExpect(jsonPath("$.media.fileName").value("a.png"));
        send(post(url()), leadToken, request("Long", "IMAGE", caption("x".repeat(1025)), id))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields['content.caption']").exists());
        String video = uploaded(leadToken, brandA.getId(), "a.mp4",
                new byte[] {0, 0, 0, 0x18, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm', 0, 0, 0, 0});
        send(post(url()), leadToken, request("Vid", "VIDEO", new LinkedHashMap<>(), video)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.media.mimeType").value("video/mp4"));
    }

    @Test
    void replacingMediaDeletesOld() throws Exception {
        String first = uploaded(leadToken, brandA.getId(), "a.png", png());
        String second = uploaded(leadToken, brandA.getId(), "b.png", png());
        JsonNode t = createImage("Img", first);
        Map<String, Object> req = request("Img", "IMAGE", caption("hi"), second);
        req.put("version", t.get("version").asLong());
        send(put(url() + "/" + t.get("id").asString()), leadToken, req).andExpect(status().isOk())
                .andExpect(jsonPath("$.media.mediaId").value(second));
        assertThat(exists(first)).isFalse();
        assertThat(exists(second)).isTrue();
    }

    @Test
    void failedReplaceKeepsOldMedia() throws Exception {
        String first = uploaded(leadToken, brandA.getId(), "a.png", png());
        String second = uploaded(leadToken, brandA.getId(), "b.png", png());
        JsonNode t = createImage("Img", first);
        Map<String, Object> req = request("Img", "IMAGE", caption("hi"), second);
        req.put("version", t.get("version").asLong() + 5);
        send(put(url() + "/" + t.get("id").asString()), leadToken, req).andExpect(status().isConflict());
        assertThat(exists(first)).isTrue();
        assertThat(exists(second)).isTrue();
    }

    @Test
    void deletingTemplateDeletesMedia() throws Exception {
        String id = uploaded(leadToken, brandA.getId(), "a.png", png());
        JsonNode t = createImage("Img", id);
        send(delete(url() + "/" + t.get("id").asString()), leadToken, null).andExpect(status().isNoContent());
        assertThat(exists(id)).isFalse();
    }

    @Test
    void usedTemplateKeepsMediaWhenDeleteRefused() throws Exception {
        String id = uploaded(leadToken, brandA.getId(), "a.png", png());
        JsonNode t = createImage("Img", id);
        MessageTemplate stored = templates.findById(t.get("id").asString()).orElseThrow();
        stored.setLastUsedAt(Instant.now());
        templates.save(stored);
        send(delete(url() + "/" + t.get("id").asString()), leadToken, null).andExpect(status().isConflict());
        assertThat(exists(id)).isTrue();
    }

    @Test
    void sweepDeletesOnlyOldOrphans() throws Exception {
        String referenced = uploaded(leadToken, brandA.getId(), "a.png", png());
        createImage("Img", referenced);
        String fresh = uploaded(leadToken, brandA.getId(), "b.png", png());
        String old = gridFs.store(new ByteArrayInputStream(png()), "old.png", "image/png", new Document()
                .append("workspaceId", brandA.getId()).append("fileName", "old.png").append("mimeType", "image/png")
                .append("size", 40L).append("opus", false).append("uploadedBy", "lead")
                .append("uploadedAt", Date.from(Instant.now().minus(48, ChronoUnit.HOURS)))).toHexString();

        assertThat(media.sweepOrphans(Instant.now().minus(24, ChronoUnit.HOURS))).isEqualTo(1);
        assertThat(exists(old)).isFalse();
        assertThat(exists(fresh)).isTrue();
        assertThat(exists(referenced)).isTrue();

        assertThat(media.sweepOrphans(Instant.now().plus(1, ChronoUnit.HOURS))).isEqualTo(1);
        assertThat(exists(fresh)).isFalse();
        assertThat(exists(referenced)).isTrue();
        assertThat(media.sweepOrphans(Instant.now().plus(1, ChronoUnit.HOURS))).isZero();
    }

    @Test
    void copyMakesAnIndependentFileInTheTargetBrand() throws Exception {
        String id = uploaded(leadToken, brandA.getId(), "a.png", png());
        String copy = media.copy(brandA.getId(), id, brandB.getId());
        assertThat(copy).isNotEqualTo(id);
        TemplateMediaService.MediaFile f = media.open(brandB.getId(), copy);
        assertThat(f.fileName()).isEqualTo("a.png");
        assertThat(f.mimeType()).isEqualTo("image/png");
        assertThat(f.resource().getInputStream().readAllBytes()).isEqualTo(png());
        media.delete(id);
        assertThat(exists(copy)).isTrue();
        assertThatThrownBy(() -> media.copy(brandB.getId(), id, brandA.getId())).isInstanceOf(NotFoundException.class);
    }

    @Test
    void agentCanDownloadNotUpload() throws Exception {
        String id = uploaded(leadToken, brandA.getId(), "a.png", png());
        upload(agentToken, brandA.getId(), "x.png", png()).andExpect(status().isForbidden());
        send(get(mediaUrl() + "/" + id), agentToken, null).andExpect(status().isOk());
        send(get(mediaUrl() + "/" + id), wmBToken, null).andExpect(status().isNotFound());
        send(get(mediaUrl() + "/" + id), null, null).andExpect(status().isUnauthorized());
    }

    @Test
    void schedulingUsesBootsTaskSchedulerNotTheWebSocketTimer() {
        assertThat(context.getBeansOfType(TaskScheduler.class)).containsOnlyKeys("taskScheduler");
        assertThat(context.getBean(TemplateMediaSweeper.class)).isNotNull();
    }
}
