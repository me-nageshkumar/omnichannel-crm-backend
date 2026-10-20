package me.nageshk.omnichannel.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class TemplateContentIT extends TemplateApiTestBase {

    @SafeVarargs
    private static Map<String, Object> request(String type, Map<String, Object> content, Map<String, Object>... settings) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("name", "T-" + System.nanoTime());
        r.put("type", type);
        r.put("content", content);
        r.put("placeholders", new ArrayList<>(List.of(settings)));
        return r;
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private Object fieldsOf(Map<String, Object> req, String field) throws Exception {
        return json(send(post(url()), leadToken, req).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION")).andExpect(jsonPath("$.fields['" + field + "']").exists()))
                .get("fields").get(field).asString();
    }

    private void rejected(Map<String, Object> req, String field) throws Exception {
        send(post(url()), leadToken, req).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION")).andExpect(jsonPath("$.fields['" + field + "']").exists());
    }

    private JsonNode created(Map<String, Object> req) throws Exception {
        return json(send(post(url()), leadToken, req).andExpect(status().isCreated()));
    }

    @Test
    void locationLimits() throws Exception {
        JsonNode t = created(request("LOCATION", map("latitude", 12.97, "longitude", 77.59, "name", " HQ ",
                "address", "{{ street }}, Bengaluru"), setting("street", null, "Street", null, true)));
        JsonNode c = t.get("content");
        assertThat(t.get("type").asString()).isEqualTo("LOCATION");
        assertThat(c.get("latitude").asDouble()).isEqualTo(12.97);
        assertThat(c.get("longitude").asDouble()).isEqualTo(77.59);
        assertThat(c.get("name").asString()).isEqualTo("HQ");
        assertThat(c.get("address").asString()).isEqualTo("{{street}}, Bengaluru");
        assertThat(c.get("body").isNull()).isTrue();
        assertThat(t.get("placeholders")).hasSize(1);

        created(request("LOCATION", map("latitude", -90.0, "longitude", 180.0)));
        created(request("LOCATION", map("latitude", 90, "longitude", -180, "name", "n".repeat(256),
                "address", "a".repeat(256))));
        rejected(request("LOCATION", map("latitude", 90.1, "longitude", 0)), "content.latitude");
        rejected(request("LOCATION", map("latitude", -90.1, "longitude", 0)), "content.latitude");
        rejected(request("LOCATION", map("latitude", 0, "longitude", 180.1)), "content.longitude");
        rejected(request("LOCATION", map("latitude", 0, "longitude", -180.1)), "content.longitude");
        rejected(request("LOCATION", map("longitude", 0)), "content.latitude");
        rejected(request("LOCATION", map("latitude", 0)), "content.longitude");
        rejected(request("LOCATION", map("latitude", 0, "longitude", 0, "name", "n".repeat(257))), "content.name");
        rejected(request("LOCATION", map("latitude", 0, "longitude", 0, "address", "a".repeat(257))), "content.address");
    }

    @Test
    void placeholderInLatitudeIs400() throws Exception {
        send(post(url()), leadToken, request("LOCATION", map("latitude", "{{lat}}", "longitude", 0),
                setting("lat", null, "Lat", null, true))).andExpect(status().isBadRequest());
    }

    @Test
    void contactCardValidatesPhoneAndEmail() throws Exception {
        JsonNode t = created(request("CONTACT_CARD", map("fullName", " Asha Rao ", "phone", "+91 98765-43210",
                "organization", "Acme", "email", "asha@acme.com")));
        JsonNode c = t.get("content");
        assertThat(c.get("fullName").asString()).isEqualTo("Asha Rao");
        assertThat(c.get("phone").asString()).isEqualTo("919876543210");
        assertThat(c.get("organization").asString()).isEqualTo("Acme");
        assertThat(c.get("email").asString()).isEqualTo("asha@acme.com");

        JsonNode minimal = created(request("CONTACT_CARD", map("fullName", "A", "phone", "12345678")));
        assertThat(minimal.get("content").get("email").isNull()).isTrue();
        assertThat(minimal.get("content").get("organization").isNull()).isTrue();

        rejected(request("CONTACT_CARD", map("phone", "12345678")), "content.fullName");
        rejected(request("CONTACT_CARD", map("fullName", " ", "phone", "12345678")), "content.fullName");
        rejected(request("CONTACT_CARD", map("fullName", "n".repeat(257), "phone", "12345678")), "content.fullName");
        rejected(request("CONTACT_CARD", map("fullName", "A")), "content.phone");
        rejected(request("CONTACT_CARD", map("fullName", "A", "phone", "1234567")), "content.phone");
        rejected(request("CONTACT_CARD", map("fullName", "A", "phone", "0123456789")), "content.phone");
        rejected(request("CONTACT_CARD", map("fullName", "A", "phone", "1234567890123456")), "content.phone");
        rejected(request("CONTACT_CARD", map("fullName", "A", "phone", "12ab5678")), "content.phone");
        rejected(request("CONTACT_CARD", map("fullName", "A", "phone", "12345678", "email", "nope")), "content.email");
        rejected(request("CONTACT_CARD", map("fullName", "A", "phone", "12345678", "email", "a@b")), "content.email");
        rejected(request("CONTACT_CARD", map("fullName", "A", "phone", "12345678", "email", "a@@b.com")), "content.email");
        rejected(request("CONTACT_CARD", map("fullName", "A", "phone", "12345678", "email", "@b.com")), "content.email");
        rejected(request("CONTACT_CARD", map("fullName", "A", "phone", "12345678", "email", "a@b.com ".repeat(40))),
                "content.email");
        rejected(request("CONTACT_CARD", map("fullName", "A", "phone", "12345678", "organization", "o".repeat(257))),
                "content.organization");
    }

    @Test
    void contactCardPlaceholderOnlyInNameAndOrganization() throws Exception {
        JsonNode t = created(request("CONTACT_CARD", map("fullName", "{{ contact.name }}", "phone", "12345678",
                "organization", "{{company}}"), setting("contact.name", "Friend", null, null, null),
                setting("company", null, "Company", null, true)));
        assertThat(t.get("content").get("fullName").asString()).isEqualTo("{{contact.name}}");
        assertThat(t.get("placeholders")).hasSize(2);

        assertThat(fieldsOf(request("CONTACT_CARD", map("fullName", "A", "phone", "{{p}}"),
                setting("p", null, "P", null, true)), "content.phone")).isEqualTo("placeholders are not allowed here");
        assertThat(fieldsOf(request("CONTACT_CARD", map("fullName", "A", "phone", "12345678", "email", "{{e}}"),
                setting("e", null, "E", null, true)), "content.email")).isEqualTo("placeholders are not allowed here");
    }

    @Test
    void pollNeedsTwoToTwelveDistinctOptions() throws Exception {
        JsonNode t = created(request("POLL", map("question", " Which {{ thing }}? ", "options", List.of(" Red ", "Blue"),
                "allowMultiple", true), setting("thing", null, "Thing", null, true)));
        JsonNode c = t.get("content");
        assertThat(c.get("question").asString()).isEqualTo("Which {{thing}}?");
        assertThat(c.get("options")).hasSize(2);
        assertThat(c.get("options").get(0).asString()).isEqualTo("Red");
        assertThat(c.get("allowMultiple").asBoolean()).isTrue();

        JsonNode defaults = created(request("POLL", map("question", "Q", "options", List.of("a", "b"))));
        assertThat(defaults.get("content").get("allowMultiple").asBoolean()).isFalse();

        created(request("POLL", map("question", "q".repeat(255),
                "options", java.util.stream.IntStream.rangeClosed(1, 12).mapToObj(i -> "o" + i).toList())));
        created(request("POLL", map("question", "Q", "options", List.of("o".repeat(100), "p"))));
        rejected(request("POLL", map("question", "Q", "options", List.of("a"))), "content.options");
        rejected(request("POLL", map("question", "Q")), "content.options");
        rejected(request("POLL", map("question", "Q",
                "options", java.util.stream.IntStream.rangeClosed(1, 13).mapToObj(i -> "o" + i).toList())),
                "content.options");
        rejected(request("POLL", map("question", "Q", "options", List.of("Yes", "yes"))), "content.options");
        rejected(request("POLL", map("question", "Q", "options", List.of("a", " "))), "content.options");
        rejected(request("POLL", map("question", "Q", "options", List.of("a", "o".repeat(101)))), "content.options");
        rejected(request("POLL", map("question", "Q", "options", List.of("{{ x }}", "{{x}}"),
                "allowMultiple", false), setting("x", null, "X", null, true)), "content.options");
        rejected(request("POLL", map("question", " ", "options", List.of("a", "b"))), "content.question");
        rejected(request("POLL", map("question", "q".repeat(256), "options", List.of("a", "b"))), "content.question");
    }

    @Test
    void mediaTypesNeedMedia() throws Exception {
        assertThat(fieldsOf(request("IMAGE", map("caption", "hi")), "mediaId")).isEqualTo("is required");
        assertThat(fieldsOf(request("AUDIO", map("voiceNote", true)), "mediaId")).isEqualTo("is required");
        rejected(request("DOCUMENT", map("caption", "x".repeat(1025))), "content.caption");
    }
}
