package me.nageshk.omnichannel.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class TemplateApiIT extends TemplateApiTestBase {

    private JsonNode create(String token, Map<String, Object> req) throws Exception {
        return json(send(post(url()), token, req).andExpect(status().isCreated()));
    }

    private JsonNode create(String name, String body) throws Exception {
        return create(leadToken, text(name, body));
    }

    @Test
    void teamLeadCreatesTextTemplate() throws Exception {
        Map<String, Object> req = text("Welcome", "Hi {{ contact.name }}, your order {{order_id}} is ready",
                setting("contact.name", "there", null, null, null),
                setting("order_id", null, "Order id", "A-1", true));
        req.put("category", "Greeting");
        JsonNode t = create(leadToken, req);

        assertThat(t.get("id").asString()).isNotBlank();
        assertThat(t.get("workspaceId").asString()).isEqualTo(brandA.getId());
        assertThat(t.get("channel").asString()).isEqualTo("WHATSAPP_WEB");
        assertThat(t.get("name").asString()).isEqualTo("Welcome");
        assertThat(t.get("category").asString()).isEqualTo("Greeting");
        assertThat(t.get("type").asString()).isEqualTo("TEXT");
        assertThat(t.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(t.get("media").isNull()).isTrue();
        assertThat(t.get("lastUsedAt").isNull()).isTrue();
        assertThat(t.get("version").asLong()).isZero();
        assertThat(t.get("warnings")).isEmpty();
        assertThat(t.get("createdBy").get("username").asString()).isEqualTo("lead");
        assertThat(t.get("createdBy").get("name").asString()).isEqualTo("Test lead");
        assertThat(t.get("updatedBy").get("username").asString()).isEqualTo("lead");
        assertThat(t.get("creationTime").isNull()).isFalse();

        JsonNode c = t.get("content");
        assertThat(c.get("body").asString()).isEqualTo("Hi {{contact.name}}, your order {{order_id}} is ready");
        assertThat(c.get("linkPreview").asBoolean()).isTrue();
        assertThat(c.get("caption").isNull()).isTrue();
        assertThat(c.get("options").isNull()).isTrue();

        JsonNode ps = t.get("placeholders");
        assertThat(ps).hasSize(2);
        assertThat(ps.get(0).get("name").asString()).isEqualTo("contact.name");
        assertThat(ps.get(0).get("kind").asString()).isEqualTo("CONTACT_FIELD");
        assertThat(ps.get(0).get("fallback").asString()).isEqualTo("there");
        assertThat(ps.get(0).get("missing").asBoolean()).isFalse();
        assertThat(ps.get(1).get("name").asString()).isEqualTo("order_id");
        assertThat(ps.get(1).get("kind").asString()).isEqualTo("AGENT");
        assertThat(ps.get(1).get("label").asString()).isEqualTo("Order id");
        assertThat(ps.get(1).get("example").asString()).isEqualTo("A-1");
        assertThat(ps.get(1).get("required").asBoolean()).isTrue();

        send(get(url() + "/" + t.get("id").asString()), agentToken, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Welcome"));
        MessageTemplate stored = templates.findById(t.get("id").asString()).orElseThrow();
        assertThat(stored.getNameKey()).isEqualTo("welcome");
        assertThat(stored.getCreatedByUsername()).isEqualTo("lead");
        assertThat(stored.getContent().getCaption()).isNull();
    }

    @Test
    void linkPreviewCanBeSwitchedOff() throws Exception {
        Map<String, Object> req = text("T", "hello");
        req.put("content", Map.of("body", "hello", "linkPreview", false));
        assertThat(create(leadToken, req).get("content").get("linkPreview").asBoolean()).isFalse();
    }

    @Test
    void nameDuplicateIgnoringCaseIs409() throws Exception {
        create("Welcome", "hi");
        send(post(url()), leadToken, text("  WELCOME ", "other")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE")).andExpect(jsonPath("$.field").value("name"))
                .andExpect(jsonPath("$.message").value("\"WELCOME\" already exists in this brand"));
        // the same name in another brand is fine
        send(post(base(brandB) + "/templates"), wmBToken, text("Welcome", "hi")).andExpect(status().isCreated());
    }

    @Test
    void renamingOntoAnotherNameIs409ButKeepingOwnNameIsFine() throws Exception {
        create("One", "hi");
        JsonNode two = create("Two", "hi");
        Map<String, Object> req = text("one", "hi");
        req.put("version", two.get("version").asLong());
        send(put(url() + "/" + two.get("id").asString()), leadToken, req).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE"));
        req.put("name", "TWO");
        send(put(url() + "/" + two.get("id").asString()), leadToken, req).andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("TWO"));
    }

    @Test
    void archivedNameStillBlocks() throws Exception {
        JsonNode t = create("Welcome", "hi");
        send(post(url() + "/" + t.get("id").asString() + "/archive"), leadToken, null).andExpect(status().isOk());
        send(post(url()), leadToken, text("welcome", "hi")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE"));
    }

    @Test
    void bodyLimits() throws Exception {
        send(post(url()), leadToken, text("A", "")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION")).andExpect(jsonPath("$.fields['content.body']").exists());
        send(post(url()), leadToken, text("B", "x".repeat(4097))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields['content.body']").exists());
        send(post(url()), leadToken, text("C", "x".repeat(4096))).andExpect(status().isCreated());
    }

    @Test
    void nameAndCategoryAndTypeLimits() throws Exception {
        send(post(url()), leadToken, text(" ", "hi")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.name").exists());
        send(post(url()), leadToken, text("n".repeat(101), "hi")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.name").exists());
        Map<String, Object> longCategory = text("A", "hi");
        longCategory.put("category", "c".repeat(41));
        send(post(url()), leadToken, longCategory).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.category").exists());
        Map<String, Object> noType = text("A", "hi");
        noType.remove("type");
        send(post(url()), leadToken, noType).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.type").exists());
        Map<String, Object> poll = text("A", "hi");
        poll.put("type", "POLL");
        send(post(url()), leadToken, poll).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.type").value("not supported yet"));
        Map<String, Object> media = text("A", "hi");
        media.put("mediaId", "abc");
        send(post(url()), leadToken, media).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.mediaId").exists());
        send(post(url()), leadToken, null).andExpect(status().isBadRequest());
    }

    @Test
    void badPlaceholderIs400OnBody() throws Exception {
        send(post(url()), leadToken, text("A", "Hi {{#each}}")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields['content.body']").exists());
        send(post(url()), leadToken, text("B", "Hi {{Name}}")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields['content.body']").exists());
        send(post(url()), leadToken, text("C", "Hi {{contact.email}}")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields['content.body']").exists());
    }

    @Test
    void twentyOnePlaceholdersIs400() throws Exception {
        List<String> names = new ArrayList<>();
        List<Map<String, Object>> settings = new ArrayList<>();
        for (int i = 1; i <= 21; i++) {
            names.add("{{p" + i + "}}");
            settings.add(setting("p" + i, null, "P" + i, null, false));
        }
        Map<String, Object> req = text("A", String.join(" ", names));
        req.put("placeholders", settings);
        send(post(url()), leadToken, req).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields['content.body']").exists());
        // 20 is fine
        names.remove(20);
        settings.remove(20);
        req = text("B", String.join(" ", names));
        req.put("placeholders", settings);
        send(post(url()), leadToken, req).andExpect(status().isCreated());
    }

    @Test
    void agentPlaceholderNeedsLabel() throws Exception {
        send(post(url()), leadToken, text("A", "{{x}}", setting("x", null, " ", null, false)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields['placeholders.x.label']").exists());
        send(post(url()), leadToken, text("B", "{{x}}", setting("x", null, "l".repeat(61), null, false)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields['placeholders.x.label']").exists());
        send(post(url()), leadToken, text("C", "{{x}}", setting("x", null, "Ok", "e".repeat(201), false)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields['placeholders.x.example']").exists());
        send(post(url()), leadToken, text("D", "{{x}}", setting("x", "f".repeat(201), "Ok", null, false)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields['placeholders.x.fallback']").exists());
    }

    @Test
    void requiredAgentPlaceholderRejectsFallback() throws Exception {
        send(post(url()), leadToken, text("A", "{{x}}", setting("x", "none", "X", null, true)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields['placeholders.x.fallback']").exists());
        // optional agent placeholder may carry a fallback
        send(post(url()), leadToken, text("B", "{{x}}", setting("x", "none", "X", null, false)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.placeholders[0].fallback").value("none"));
    }

    @Test
    void contactPlaceholderNeedsFallback() throws Exception {
        send(post(url()), leadToken, text("A", "{{contact.name}}", setting("contact.name", null, null, null, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields['placeholders.contact.name.fallback']").exists());
        send(post(url()), leadToken, text("B", "{{contact.phone}}", setting("contact.phone", "  ", null, null, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields['placeholders.contact.phone.fallback']").exists());
    }

    @Test
    void attributePlaceholderIsNotSupportedYet() throws Exception {
        send(post(url()), leadToken,
                text("A", "{{contact.attributes.city}}", setting("contact.attributes.city", "there", null, null, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields['placeholders.contact.attributes.city']").value("not supported yet"));
    }

    @Test
    void settingsForAbsentPlaceholderAreDropped() throws Exception {
        JsonNode t = create(leadToken, text("A", "Hi {{x}}", setting("x", null, "X", null, false),
                setting("gone", "g", "Gone", null, false)));
        assertThat(t.get("placeholders")).hasSize(1);
        assertThat(t.get("placeholders").get(0).get("name").asString()).isEqualTo("x");
        assertThat(templates.findById(t.get("id").asString()).orElseThrow().getPlaceholders()).hasSize(1);
    }

    @Test
    void missingSettingsIs400() throws Exception {
        send(post(url()), leadToken, text("A", "Hi {{x}} {{contact.name}}", setting("contact.name", "there", null, null, null)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields['placeholders.x']").exists());
        Map<String, Object> none = text("B", "Hi {{x}}");
        none.remove("placeholders");
        send(post(url()), leadToken, none).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields['placeholders.x']").exists());
    }

    @Test
    void updateReplacesFieldsAndRecordsWhoChanged() throws Exception {
        JsonNode t = create("Welcome", "hi");
        Map<String, Object> req = text("Welcome 2", "hello {{x}}", setting("x", null, "X", null, false));
        req.put("category", "Cat");
        req.put("type", "POLL"); // ignored on PUT
        req.put("version", t.get("version").asLong());
        JsonNode u = json(send(put(url() + "/" + t.get("id").asString()), wmToken, req).andExpect(status().isOk()));
        assertThat(u.get("name").asString()).isEqualTo("Welcome 2");
        assertThat(u.get("type").asString()).isEqualTo("TEXT");
        assertThat(u.get("category").asString()).isEqualTo("Cat");
        assertThat(u.get("content").get("body").asString()).isEqualTo("hello {{x}}");
        assertThat(u.get("version").asLong()).isEqualTo(1);
        assertThat(u.get("createdBy").get("username").asString()).isEqualTo("lead");
        assertThat(u.get("updatedBy").get("username").asString()).isEqualTo("wm");
        assertThat(u.get("updatedBy").get("name").asString()).isEqualTo("Test wm");
    }

    @Test
    void putWithoutVersionIs400() throws Exception {
        JsonNode t = create("Welcome", "hi");
        send(put(url() + "/" + t.get("id").asString()), leadToken, text("Welcome", "hi"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.version").exists());
    }

    @Test
    void staleVersionIs409() throws Exception {
        JsonNode t = create("Welcome", "hi");
        Map<String, Object> req = text("Welcome", "changed");
        req.put("version", t.get("version").asLong());
        send(put(url() + "/" + t.get("id").asString()), leadToken, req).andExpect(status().isOk());
        send(put(url() + "/" + t.get("id").asString()), leadToken, req).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
        assertThat(templates.findById(t.get("id").asString()).orElseThrow().getContent().getBody()).isEqualTo("changed");
    }

    @Test
    void listFiltersByQTypeCategoryStatus() throws Exception {
        Map<String, Object> b = text("bravo", "x");
        b.put("category", "Promo");
        create(leadToken, b);
        create("Alpha", "x");
        Map<String, Object> c = text("Charlie promo", "x");
        c.put("category", "Support");
        create(leadToken, c);
        JsonNode old = create("Delta", "x");
        send(post(url() + "/" + old.get("id").asString() + "/archive"), leadToken, null).andExpect(status().isOk());

        // default: ACTIVE only, sorted by name ignoring case
        send(get(url()), agentToken, null).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[0].name").value("Alpha")).andExpect(jsonPath("$[1].name").value("bravo"))
                .andExpect(jsonPath("$[2].name").value("Charlie promo"));
        send(get(url()).param("q", "PROMO"), agentToken, null).andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].name").value("Charlie promo"));
        send(get(url()).param("q", "a.b"), agentToken, null).andExpect(jsonPath("$", hasSize(0)));
        send(get(url()).param("category", "Promo"), agentToken, null).andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].name").value("bravo"));
        send(get(url()).param("type", "TEXT"), agentToken, null).andExpect(jsonPath("$", hasSize(3)));
        send(get(url()).param("type", "POLL"), agentToken, null).andExpect(jsonPath("$", hasSize(0)));
        send(get(url()).param("status", "ARCHIVED"), agentToken, null).andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].name").value("Delta"));
        // other brand sees nothing
        send(get(base(brandB) + "/templates"), wmBToken, null).andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void categoriesDistinctSorted() throws Exception {
        for (String[] nc : new String[][] {{"a", "Zeta"}, {"b", "alpha"}, {"c", "Zeta"}, {"d", null}, {"e", "Beta"}}) {
            Map<String, Object> r = text(nc[0], "x");
            if (nc[1] != null) r.put("category", nc[1]);
            create(leadToken, r);
        }
        send(get(url() + "/categories"), agentToken, null).andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3))).andExpect(jsonPath("$[0]").value("alpha"))
                .andExpect(jsonPath("$[1]").value("Beta")).andExpect(jsonPath("$[2]").value("Zeta"));
        send(post(base(brandB) + "/templates"), wmBToken, text("z", "x")).andExpect(status().isCreated());
        send(get(base(brandB) + "/templates/categories"), wmBToken, null).andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void archiveHidesFromDefaultList() throws Exception {
        JsonNode t = create("Welcome", "hi");
        send(post(url() + "/" + t.get("id").asString() + "/archive"), leadToken, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ARCHIVED"));
        send(get(url()), leadToken, null).andExpect(jsonPath("$", hasSize(0)));
        send(get(url()).param("status", "ARCHIVED"), leadToken, null).andExpect(jsonPath("$", hasSize(1)));
        send(get(url() + "/" + t.get("id").asString()), leadToken, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ARCHIVED"));
    }

    @Test
    void unarchive() throws Exception {
        JsonNode t = create("Welcome", "hi");
        String id = t.get("id").asString();
        send(post(url() + "/" + id + "/archive"), leadToken, null).andExpect(status().isOk());
        send(post(url() + "/" + id + "/unarchive"), leadToken, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        send(get(url()), leadToken, null).andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void deleteUnusedIs204() throws Exception {
        JsonNode t = create("Welcome", "hi");
        send(delete(url() + "/" + t.get("id").asString()), leadToken, null).andExpect(status().isNoContent());
        assertThat(templates.findById(t.get("id").asString())).isEmpty();
        send(get(url() + "/" + t.get("id").asString()), leadToken, null).andExpect(status().isNotFound());
    }

    @Test
    void deleteUsedIs409() throws Exception {
        JsonNode t = create("Welcome", "hi");
        MessageTemplate stored = templates.findById(t.get("id").asString()).orElseThrow();
        stored.setLastUsedAt(Instant.now());
        templates.save(stored);
        send(delete(url() + "/" + t.get("id").asString()), leadToken, null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TEMPLATE_IN_USE"))
                .andExpect(jsonPath("$.message").value("This template has been used; archive it instead"));
        assertThat(templates.findById(t.get("id").asString())).isPresent();
        // a used template can still be archived
        send(post(url() + "/" + t.get("id").asString() + "/archive"), leadToken, null).andExpect(status().isOk());
    }

    @Test
    void agentCanViewNotManage() throws Exception {
        JsonNode t = create("Welcome", "hi");
        String id = t.get("id").asString();
        send(get(url()), agentToken, null).andExpect(status().isOk());
        send(get(url() + "/" + id), agentToken, null).andExpect(status().isOk());
        send(get(url() + "/categories"), agentToken, null).andExpect(status().isOk());
        Map<String, Object> req = text("X", "y");
        req.put("version", 0);
        send(post(url()), agentToken, text("X", "y")).andExpect(status().isForbidden());
        send(put(url() + "/" + id), agentToken, req).andExpect(status().isForbidden());
        send(post(url() + "/" + id + "/archive"), agentToken, null).andExpect(status().isForbidden());
        send(post(url() + "/" + id + "/unarchive"), agentToken, null).andExpect(status().isForbidden());
        send(delete(url() + "/" + id), agentToken, null).andExpect(status().isForbidden());
    }

    @Test
    void otherBrandIs404() throws Exception {
        JsonNode theirs = json(send(post(base(brandB) + "/templates"), wmBToken, text("Theirs", "hi"))
                .andExpect(status().isCreated()));
        String id = theirs.get("id").asString();
        Map<String, Object> req = text("X", "y");
        req.put("version", 0);
        // a manager of brand A reaching for brand B's template through brand A's URL
        send(get(url() + "/" + id), wmToken, null).andExpect(status().isNotFound());
        send(put(url() + "/" + id), wmToken, req).andExpect(status().isNotFound());
        send(post(url() + "/" + id + "/archive"), wmToken, null).andExpect(status().isNotFound());
        send(post(url() + "/" + id + "/unarchive"), wmToken, null).andExpect(status().isNotFound());
        send(delete(url() + "/" + id), wmToken, null).andExpect(status().isNotFound());
        // and through brand B's URL without being its member: the workspace itself is not found
        send(get(base(brandB) + "/templates/" + id), wmToken, null).andExpect(status().isNotFound());
        assertThat(templates.findById(id)).isPresent();
    }

    @Test
    void accountManagerHasNoAccess() throws Exception {
        // platform staff have no brand access, so AccessService reports the workspace as not found
        send(get(url()), accountManagerToken, null).andExpect(status().isNotFound());
        send(post(url()), accountManagerToken, text("X", "y")).andExpect(status().isNotFound());
        assertThat(templates.findAll()).isEmpty();
    }

    @Test
    void customerAdminManagesAcrossBrands() throws Exception {
        send(post(url()), bossToken, text("A", "x")).andExpect(status().isCreated());
        send(post(base(brandB) + "/templates"), bossToken, text("A", "x")).andExpect(status().isCreated());
    }

    @Test
    void unauthenticatedIs401() throws Exception {
        send(get(url()), null, null).andExpect(status().isUnauthorized());
    }
}
