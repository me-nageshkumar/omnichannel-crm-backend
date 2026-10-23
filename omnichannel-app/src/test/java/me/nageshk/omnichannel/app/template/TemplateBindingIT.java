package me.nageshk.omnichannel.app.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import me.nageshk.omnichannel.db.contact.ContactAttribute;
import me.nageshk.omnichannel.db.template.MessageTemplate;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class TemplateBindingIT extends TemplateApiTestBase {

    private static final String CITY = "contact.attributes.city";

    private ContactAttribute attribute(String name, String key) {
        ContactAttribute a = new ContactAttribute();
        a.setWorkspaceId(brandA.getId());
        a.setName(name);
        a.setKey(key);
        return attributes.save(a);
    }

    private Map<String, Object> cityTemplate(String name) {
        return text(name, "Hello from {{ contact.attributes.city }}", setting(CITY, "your city", null, null, null));
    }

    private JsonNode create(Map<String, Object> req) throws Exception {
        return json(send(post(url()), leadToken, req).andExpect(status().isCreated()));
    }

    private JsonNode read(String id) throws Exception {
        return json(send(get(url() + "/" + id), leadToken, null).andExpect(status().isOk()));
    }

    @Test
    void attributePlaceholderBindsToId() throws Exception {
        ContactAttribute city = attribute("City", "city");
        JsonNode t = create(cityTemplate("A"));

        assertThat(t.get("content").get("body").asString()).isEqualTo("Hello from {{contact.attributes.city}}");
        JsonNode p = t.get("placeholders").get(0);
        assertThat(p.get("name").asString()).isEqualTo(CITY);
        assertThat(p.get("kind").asString()).isEqualTo("CONTACT_ATTRIBUTE");
        assertThat(p.get("attributeKey").asString()).isEqualTo("city");
        assertThat(p.get("attributeId").asString()).isEqualTo(city.getId());
        assertThat(p.get("attributeName").asString()).isEqualTo("City");
        assertThat(p.get("missing").asBoolean()).isFalse();
        assertThat(p.get("fallback").asString()).isEqualTo("your city");
        assertThat(t.get("warnings")).isEmpty();
        MessageTemplate stored = templates.findById(t.get("id").asString()).orElseThrow();
        assertThat(stored.getPlaceholders().get(0).getAttributeId()).isEqualTo(city.getId());
    }

    @Test
    void attributeFromAnotherBrandDoesNotBind() throws Exception {
        ContactAttribute other = new ContactAttribute();
        other.setWorkspaceId(brandB.getId());
        other.setName("City");
        other.setKey("city");
        attributes.save(other);
        send(post(url()), leadToken, cityTemplate("A")).andExpect(status().isBadRequest());
    }

    @Test
    void unknownKeyIs400() throws Exception {
        send(post(url()), leadToken, cityTemplate("A")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION"))
                .andExpect(jsonPath("$.fields['placeholders.contact.attributes.city']")
                        .value("Unknown contact attribute \"city\""));
    }

    @Test
    void attributePlaceholderNeedsFallback() throws Exception {
        attribute("City", "city");
        send(post(url()), leadToken, text("A", "{{contact.attributes.city}}", setting(CITY, " ", null, null, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields['placeholders.contact.attributes.city.fallback']").exists());
    }

    @Test
    void renameAttributeKeepsBinding() throws Exception {
        ContactAttribute city = attribute("City", "city");
        String id = create(cityTemplate("A")).get("id").asString();

        city.setName("Home town");
        attributes.save(city);

        JsonNode t = read(id);
        assertThat(t.get("warnings")).isEmpty();
        JsonNode p = t.get("placeholders").get(0);
        assertThat(p.get("attributeName").asString()).isEqualTo("Home town");
        assertThat(p.get("attributeId").asString()).isEqualTo(city.getId());
        assertThat(p.get("missing").asBoolean()).isFalse();
    }

    @Test
    void deletedAttributeWarnsAndSaveKeepsBinding() throws Exception {
        ContactAttribute city = attribute("City", "city");
        JsonNode created = create(cityTemplate("A"));
        String id = created.get("id").asString();
        attributes.delete(city);

        JsonNode t = read(id);
        assertThat(t.get("warnings")).hasSize(1);
        assertThat(t.get("warnings").get(0).asString()).isEqualTo("Attribute \"city\" was deleted — fallback will be used");
        JsonNode p = t.get("placeholders").get(0);
        assertThat(p.get("missing").asBoolean()).isTrue();
        assertThat(p.get("attributeName").isNull()).isTrue();
        assertThat(p.get("attributeId").asString()).isEqualTo(city.getId());

        // list carries the same warning
        JsonNode list = json(send(get(url()), leadToken, null).andExpect(status().isOk()));
        assertThat(list.get(0).get("warnings").get(0).asString()).contains("was deleted");

        Map<String, Object> edit = cityTemplate("A");
        edit.put("version", t.get("version").asLong());
        JsonNode saved = json(send(put(url() + "/" + id), leadToken, edit).andExpect(status().isOk()));
        assertThat(saved.get("placeholders").get(0).get("attributeId").asString()).isEqualTo(city.getId());
        assertThat(saved.get("placeholders").get(0).get("missing").asBoolean()).isTrue();
        assertThat(saved.get("warnings")).hasSize(1);
    }

    @Test
    void recreatedAttributeRebindsOnSave() throws Exception {
        ContactAttribute city = attribute("City", "city");
        JsonNode created = create(cityTemplate("A"));
        String id = created.get("id").asString();
        attributes.delete(city);
        ContactAttribute again = attribute("City", "city");

        JsonNode t = read(id);
        assertThat(t.get("warnings")).hasSize(1);
        assertThat(t.get("warnings").get(0).asString())
                .isEqualTo("Attribute \"city\" was deleted — a new \"city\" exists; save the template to use it");
        assertThat(t.get("placeholders").get(0).get("missing").asBoolean()).isTrue();

        Map<String, Object> edit = cityTemplate("A");
        edit.put("version", t.get("version").asLong());
        JsonNode saved = json(send(put(url() + "/" + id), leadToken, edit).andExpect(status().isOk()));
        assertThat(saved.get("warnings")).isEmpty();
        JsonNode p = saved.get("placeholders").get(0);
        assertThat(p.get("attributeId").asString()).isEqualTo(again.getId()).isNotEqualTo(city.getId());
        assertThat(p.get("missing").asBoolean()).isFalse();
        assertThat(p.get("attributeName").asString()).isEqualTo("City");
    }

    @Test
    void newPlaceholderOnUpdateWithDeletedAttributeIs400() throws Exception {
        ContactAttribute city = attribute("City", "city");
        String id = create(text("A", "plain")).get("id").asString();
        attributes.delete(city);
        Map<String, Object> edit = cityTemplate("A");
        edit.put("version", 0);
        send(put(url() + "/" + id), leadToken, edit).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields['placeholders.contact.attributes.city']").exists());
    }
}
