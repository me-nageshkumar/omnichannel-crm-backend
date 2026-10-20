package me.nageshk.omnichannel.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import me.nageshk.omnichannel.access.BuiltInRoles;
import me.nageshk.omnichannel.contact.ContactAttribute;
import me.nageshk.omnichannel.tenant.Customer;
import me.nageshk.omnichannel.tenant.Workspace;
import me.nageshk.omnichannel.user.WorkspaceRole;
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

class TemplateDuplicateIT extends TemplateApiTestBase {

    private static final String CITY = "contact.attributes.city";

    @Autowired @Qualifier("templateMediaGridFs") GridFsTemplate gridFs;

    private ContactAttribute attribute(Workspace w, String name, String key) {
        ContactAttribute a = new ContactAttribute();
        a.setWorkspaceId(w.getId());
        a.setName(name);
        a.setKey(key);
        return attributes.save(a);
    }

    private JsonNode create(Map<String, Object> req) throws Exception {
        return json(send(post(url()), bossToken, req).andExpect(status().isCreated()));
    }

    private Map<String, Object> cityTemplate(String name) {
        return text(name, "Hello from {{contact.attributes.city}}", setting(CITY, "your city", null, null, null));
    }

    private ResultActions duplicate(String token, String id, Workspace target, String name) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("targetWorkspaceId", target.getId());
        body.put("name", name);
        return send(post(url() + "/" + id + "/duplicate"), token, body);
    }

    private static byte[] png() {
        byte[] b = new byte[40];
        byte[] sig = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
        System.arraycopy(sig, 0, b, 0, sig.length);
        b[20] = 7;
        return b;
    }

    private String uploaded() throws Exception {
        return json(mvc.perform(multipart(base(brandA) + "/template-media")
                .file(new MockMultipartFile("file", "logo.png", "application/octet-stream", png()))
                .header("Authorization", "Bearer " + leadToken)).andExpect(status().isCreated()))
                .get("mediaId").asString();
    }

    private JsonNode createImage(String name, String mediaId) throws Exception {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("name", name);
        r.put("type", "IMAGE");
        r.put("content", new LinkedHashMap<>(Map.of("caption", "hi")));
        r.put("mediaId", mediaId);
        r.put("placeholders", new ArrayList<>());
        return create(r);
    }

    private long files() {
        return gridFs.find(new Query()).into(new ArrayList<>()).size();
    }

    @Test
    void duplicateInSameBrand() throws Exception {
        JsonNode src = create(text("Hello", "Hi {{order_id}}", setting("order_id", null, "Order", "A1", true)));
        String id = src.get("id").asString();
        templates.findById(id).ifPresent(t -> {
            t.setLastUsedAt(java.time.Instant.now());
            t.setCategory("Sales");
            templates.save(t);
        });
        templates.findById(id).ifPresent(t -> {
            t.setStatus(TemplateStatus.ARCHIVED);
            templates.save(t);
        });

        JsonNode copy = json(duplicate(wmToken, id, brandA, "  Hello copy ").andExpect(status().isCreated()));

        assertThat(copy.get("id").asString()).isNotEqualTo(id);
        assertThat(copy.get("workspaceId").asString()).isEqualTo(brandA.getId());
        assertThat(copy.get("name").asString()).isEqualTo("Hello copy");
        assertThat(copy.get("category").asString()).isEqualTo("Sales");
        assertThat(copy.get("type").asString()).isEqualTo("TEXT");
        assertThat(copy.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(copy.get("lastUsedAt").isNull()).isTrue();
        assertThat(copy.get("content").get("body").asString()).isEqualTo("Hi {{order_id}}");
        JsonNode p = copy.get("placeholders").get(0);
        assertThat(p.get("name").asString()).isEqualTo("order_id");
        assertThat(p.get("kind").asString()).isEqualTo("AGENT");
        assertThat(p.get("label").asString()).isEqualTo("Order");
        assertThat(p.get("example").asString()).isEqualTo("A1");
        assertThat(p.get("required").asBoolean()).isTrue();
        assertThat(copy.get("createdBy").get("username").asString()).isEqualTo("wm");
        assertThat(copy.get("updatedBy").get("username").asString()).isEqualTo("wm");
        assertThat(src.get("createdBy").get("username").asString()).isEqualTo("boss");
        assertThat(templates.findById(copy.get("id").asString()).orElseThrow().getCustomerId())
                .isEqualTo(acme.getId());
        assertThat(templates.findById(id).orElseThrow().getStatus()).isEqualTo(TemplateStatus.ARCHIVED);
    }

    @Test
    void duplicateToSisterBrandRebindsByKey() throws Exception {
        ContactAttribute cityA = attribute(brandA, "City", "city");
        ContactAttribute cityB = attribute(brandB, "Town", "city");
        String id = create(cityTemplate("Greeting")).get("id").asString();

        JsonNode copy = json(duplicate(bossToken, id, brandB, "Greeting").andExpect(status().isCreated()));

        assertThat(copy.get("workspaceId").asString()).isEqualTo(brandB.getId());
        JsonNode p = copy.get("placeholders").get(0);
        assertThat(p.get("attributeKey").asString()).isEqualTo("city");
        assertThat(p.get("attributeId").asString()).isEqualTo(cityB.getId()).isNotEqualTo(cityA.getId());
        assertThat(p.get("attributeName").asString()).isEqualTo("Town");
        assertThat(p.get("missing").asBoolean()).isFalse();
        assertThat(p.get("fallback").asString()).isEqualTo("your city");
        assertThat(copy.get("warnings")).isEmpty();
        MessageTemplate stored = templates.findById(copy.get("id").asString()).orElseThrow();
        assertThat(stored.getWorkspaceId()).isEqualTo(brandB.getId());
        assertThat(stored.getPlaceholders().get(0).getAttributeId()).isEqualTo(cityB.getId());
    }

    @Test
    void missingKeyInTargetWarns() throws Exception {
        attribute(brandA, "City", "city");
        String id = create(cityTemplate("Greeting")).get("id").asString();

        JsonNode copy = json(duplicate(bossToken, id, brandB, "Greeting").andExpect(status().isCreated()));

        JsonNode p = copy.get("placeholders").get(0);
        assertThat(p.get("attributeKey").asString()).isEqualTo("city");
        assertThat(p.get("attributeId").isNull()).isTrue();
        assertThat(p.get("attributeName").isNull()).isTrue();
        assertThat(p.get("missing").asBoolean()).isTrue();
        assertThat(copy.get("warnings")).hasSize(1);
        assertThat(copy.get("warnings").get(0).asString())
                .isEqualTo("Attribute \"city\" was deleted — fallback will be used");
        String copyId = copy.get("id").asString();
        String copyUrl = base(brandB) + "/templates/" + copyId;

        // the key appears in the target later: the read says to save, and a save re-binds
        ContactAttribute cityB = attribute(brandB, "City", "city");
        JsonNode read = json(send(get(copyUrl), wmBToken, null).andExpect(status().isOk()));
        assertThat(read.get("warnings").get(0).asString())
                .isEqualTo("Attribute \"city\" was deleted — a new \"city\" exists; save the template to use it");
        Map<String, Object> edit = cityTemplate("Greeting");
        edit.put("version", read.get("version").asLong());
        JsonNode saved = json(send(put(copyUrl), wmBToken, edit).andExpect(status().isOk()));
        assertThat(saved.get("placeholders").get(0).get("attributeId").asString()).isEqualTo(cityB.getId());
        assertThat(saved.get("placeholders").get(0).get("missing").asBoolean()).isFalse();
        assertThat(saved.get("warnings")).isEmpty();
    }

    @Test
    void savingCopyWithStillMissingKeyKeepsItMissing() throws Exception {
        attribute(brandA, "City", "city");
        String id = create(cityTemplate("Greeting")).get("id").asString();
        JsonNode copy = json(duplicate(bossToken, id, brandB, "Greeting").andExpect(status().isCreated()));

        Map<String, Object> edit = cityTemplate("Greeting 2");
        edit.put("version", copy.get("version").asLong());
        JsonNode saved = json(send(put(base(brandB) + "/templates/" + copy.get("id").asString()), wmBToken, edit)
                .andExpect(status().isOk()));
        JsonNode p = saved.get("placeholders").get(0);
        assertThat(p.get("attributeKey").asString()).isEqualTo("city");
        assertThat(p.get("attributeId").isNull()).isTrue();
        assertThat(p.get("missing").asBoolean()).isTrue();
        assertThat(saved.get("warnings")).hasSize(1);
    }

    @Test
    void mediaIsCopiedNotShared() throws Exception {
        String mediaId = uploaded();
        String id = createImage("Logo", mediaId).get("id").asString();

        JsonNode copy = json(duplicate(bossToken, id, brandB, "Logo").andExpect(status().isCreated()));

        String copied = copy.get("media").get("mediaId").asString();
        assertThat(copied).isNotEqualTo(mediaId);
        assertThat(copy.get("media").get("fileName").asString()).isEqualTo("logo.png");
        assertThat(copy.get("media").get("mimeType").asString()).isEqualTo("image/png");
        assertThat(copy.get("media").get("size").asLong()).isEqualTo(png().length);
        assertThat(files()).isEqualTo(2);

        send(delete(url() + "/" + id), bossToken, null).andExpect(status().isNoContent());
        assertThat(files()).isEqualTo(1);

        byte[] got = send(get(base(brandB) + "/template-media/" + copied), wmBToken, null)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(got).isEqualTo(png());
        assertThat(gridFs.findOne(new Query(Criteria.where("_id").is(new ObjectId(mediaId))))).isNull();
    }

    @Test
    void noManageInTargetIs403() throws Exception {
        String id = create(text("Hello", "Hi")).get("id").asString();
        String token = testUsers.accessToken(testUsers.createInCustomer(acme.getId(), "multi", null, List.of(
                new WorkspaceRole(brandA.getId(), testUsers.roleId(BuiltInRoles.WORKSPACE_MANAGER)),
                new WorkspaceRole(brandB.getId(), testUsers.roleId(BuiltInRoles.AGENT)))));

        duplicate(token, id, brandB, "Hello").andExpect(status().isForbidden());
        assertThat(templates.findAll()).hasSize(1);
        // no manage in the source brand is stopped by the controller
        duplicate(agentToken, id, brandA, "Other").andExpect(status().isForbidden());
    }

    @Test
    void duplicateToOtherCustomerIs404() throws Exception {
        String id = create(text("Hello", "Hi")).get("id").asString();
        Customer other = testUsers.customer("Other");
        Workspace foreign = testUsers.workspace(other.getId(), "Foreign");

        duplicate(bossToken, id, foreign, "Hello").andExpect(status().isNotFound());
        send(post(url() + "/" + id + "/duplicate"), bossToken,
                Map.of("targetWorkspaceId", "000000000000000000000000", "name", "Hello"))
                .andExpect(status().isNotFound());
        assertThat(templates.findAll()).hasSize(1);
    }

    @Test
    void duplicateNameInTargetIs409() throws Exception {
        String mediaId = uploaded();
        String id = createImage("Logo", mediaId).get("id").asString();
        send(post(base(brandB) + "/templates"), bossToken, text("LOGO", "x")).andExpect(status().isCreated());

        duplicate(bossToken, id, brandB, " logo ").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE")).andExpect(jsonPath("$.field").value("name"));
        // same brand: the source's own name is taken too
        duplicate(bossToken, id, brandA, "Logo").andExpect(status().isConflict())
                .andExpect(jsonPath("$.field").value("name"));
        assertThat(templates.findAll()).hasSize(2);
        assertThat(files()).isEqualTo(1);
    }

    @Test
    void invalidNameIs400() throws Exception {
        String id = create(text("Hello", "Hi")).get("id").asString();
        duplicate(bossToken, id, brandB, "   ").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION")).andExpect(jsonPath("$.fields.name").exists());
        duplicate(bossToken, id, brandB, "x".repeat(101)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.name").exists());
        send(post(url() + "/" + id + "/duplicate"), bossToken, Map.of("name", "Copy"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.targetWorkspaceId").exists());
        assertThat(templates.findAll()).hasSize(1);
    }

    @Test
    void unknownTemplateIs404() throws Exception {
        duplicate(bossToken, "000000000000000000000000", brandB, "Copy").andExpect(status().isNotFound());
    }
}
