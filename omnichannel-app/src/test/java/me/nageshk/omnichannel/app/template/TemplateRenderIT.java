package me.nageshk.omnichannel.app.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.nageshk.omnichannel.db.access.Role;
import me.nageshk.omnichannel.db.access.RoleLevel;
import me.nageshk.omnichannel.db.access.RoleRepository;
import me.nageshk.omnichannel.db.contact.Contact;
import me.nageshk.omnichannel.db.contact.ContactAttribute;
import me.nageshk.omnichannel.db.template.MessageTemplate;
import me.nageshk.omnichannel.db.template.PlaceholderKind;
import me.nageshk.omnichannel.db.template.TemplateContent;
import me.nageshk.omnichannel.db.template.TemplatePlaceholder;
import me.nageshk.omnichannel.db.template.TemplateStatus;
import me.nageshk.omnichannel.db.template.TemplateType;
import me.nageshk.omnichannel.db.user.WorkspaceRole;
import me.nageshk.omnichannel.service.template.RenderedTemplate;
import me.nageshk.omnichannel.service.template.TemplateRenderer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.JsonNode;

class TemplateRenderIT extends TemplateApiTestBase {

    private static final String CITY = "contact.attributes.city";

    @Autowired RoleRepository roles;
    @Autowired me.nageshk.omnichannel.security.access.RoleCache roleCache;
    @Autowired TemplateRenderer renderer;

    private ContactAttribute attribute(String name, String key) {
        ContactAttribute a = new ContactAttribute();
        a.setWorkspaceId(brandA.getId());
        a.setName(name);
        a.setKey(key);
        return attributes.save(a);
    }

    private Contact contact(Map<String, String> attrs, String name, String phone) {
        Contact c = new Contact();
        c.setWorkspaceId(brandA.getId());
        c.setCustomerId(acme.getId());
        c.setName(name);
        c.setPhone(phone);
        c.setAttributes(new java.util.HashMap<>(attrs));
        return contacts.save(c);
    }

    private String create(Map<String, Object> req) throws Exception {
        return json(send(post(url()), leadToken, req).andExpect(status().isCreated())).get("id").asString();
    }

    private Map<String, Object> request(String name, String type, Map<String, Object> content,
            Map<String, Object>... settings) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("name", name);
        r.put("type", type);
        r.put("content", content);
        r.put("placeholders", new ArrayList<>(List.of(settings)));
        return r;
    }

    private JsonNode render(String token, String id, String contactId, Map<String, String> values) throws Exception {
        return json(rawRender(token, id, contactId, values).andExpect(status().isOk()));
    }

    private org.springframework.test.web.servlet.ResultActions rawRender(String token, String id, String contactId,
            Map<String, String> values) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        if (contactId != null) body.put("contactId", contactId);
        if (values != null) body.put("values", values);
        return send(post(url() + "/" + id + "/render"), token, body);
    }

    private String greeting() throws Exception {
        attribute("City", "city");
        return create(text("Greeting", "Hi {{contact.name}} from {{contact.attributes.city}} on {{contact.phone}}",
                setting("contact.name", "there", null, null, null),
                setting(CITY, "your city", null, null, null),
                setting("contact.phone", "your number", null, null, null)));
    }

    @Test
    void previewWithoutContactUsesFallbacks() throws Exception {
        String id = greeting();
        JsonNode r = render(leadToken, id, null, Map.of());
        assertThat(r.get("type").asString()).isEqualTo("TEXT");
        assertThat(r.get("content").get("body").asString()).isEqualTo("Hi there from your city on your number");
        assertThat(r.get("media").isNull()).isTrue();
        assertThat(r.get("vcard").isNull()).isTrue();
        assertThat(r.get("missing")).isEmpty();
        assertThat(r.get("warnings")).isEmpty();
    }

    @Test
    void renderWithContactFillsNameAndAttributes() throws Exception {
        String id = greeting();
        ContactAttribute city = attributes.findByWorkspaceIdAndKey(brandA.getId(), "city").orElseThrow();
        Contact c = contact(Map.of(city.getId(), "Pune"), "Asha", "919876543210");
        JsonNode r = render(leadToken, id, c.getId(), Map.of());
        assertThat(r.get("content").get("body").asString()).isEqualTo("Hi Asha from Pune on +919876543210");
    }

    @Test
    void hiddenPhoneUsesFallback() throws Exception {
        String id = greeting();
        Contact c = contact(Map.of(), "Asha", null);
        JsonNode r = render(leadToken, id, c.getId(), Map.of());
        assertThat(r.get("content").get("body").asString()).isEqualTo("Hi Asha from your city on your number");
    }

    @Test
    void deletedAttributeUsesFallbackWithWarning() throws Exception {
        String id = greeting();
        ContactAttribute city = attributes.findByWorkspaceIdAndKey(brandA.getId(), "city").orElseThrow();
        Contact c = contact(Map.of(city.getId(), "Pune"), "Asha", "919876543210");
        attributes.deleteById(city.getId());
        JsonNode r = render(leadToken, id, c.getId(), Map.of());
        assertThat(r.get("content").get("body").asString()).isEqualTo("Hi Asha from your city on +919876543210");
        assertThat(r.get("warnings")).hasSize(1);
        assertThat(r.get("warnings").get(0).asString())
                .isEqualTo("Attribute \"city\" was deleted — fallback will be used");
    }

    @Test
    void requiredAgentValueMissingIsListed() throws Exception {
        String id = create(text("Order", "Order {{order_id}} is ready",
                setting("order_id", null, "Order id", "A-1", true)));
        JsonNode r = render(leadToken, id, null, Map.of("order_id", "   "));
        assertThat(r.get("content").get("body").asString()).isEqualTo("Order [Order id] is ready");
        assertThat(r.get("missing")).hasSize(1);
        assertThat(r.get("missing").get(0).asString()).isEqualTo("order_id");

        JsonNode filled = render(leadToken, id, null, Map.of("order_id", "  A-7 ", "unknown", "ignored"));
        assertThat(filled.get("content").get("body").asString()).isEqualTo("Order A-7 is ready");
        assertThat(filled.get("missing")).isEmpty();
    }

    @Test
    void optionalAgentValueUsesFallback() throws Exception {
        String id = create(text("Note", "Note: {{note}}|{{other}}",
                setting("note", "none", "Note", null, false),
                setting("other", null, "Other", null, false)));
        JsonNode r = render(leadToken, id, null, null);
        assertThat(r.get("content").get("body").asString()).isEqualTo("Note: none|");
        assertThat(r.get("missing")).isEmpty();
    }

    @Test
    void agentValueWithBracesIsLiteral() throws Exception {
        String id = create(text("Lit", "Hi {{who}} / {{contact.name}}",
                setting("who", null, "Who", null, false), setting("contact.name", "x", null, null, null)));
        Contact c = contact(Map.of(), "Asha", "919876543210");
        String evil = "{{contact.name}} {{#a}}x{{/a}} {{> p}} {{{raw}}} {{=<% %>=}}";
        JsonNode r = render(leadToken, id, c.getId(), Map.of("who", evil));
        assertThat(r.get("content").get("body").asString()).isEqualTo("Hi " + evil + " / Asha");
    }

    @Test
    void htmlCharsNotEscaped() throws Exception {
        String id = create(text("Html", "{{v}}", setting("v", null, "V", null, false)));
        JsonNode r = render(leadToken, id, null, Map.of("v", "<b>Tom & \"Jerry's\"</b>"));
        assertThat(r.get("content").get("body").asString()).isEqualTo("<b>Tom & \"Jerry's\"</b>");
    }

    @Test
    void whatsappFormattingKept() throws Exception {
        String id = create(text("Fmt", "*Hi* _{{v}}_ ~x~ ```code```\nline2",
                setting("v", null, "V", null, false)));
        JsonNode r = render(leadToken, id, null, Map.of("v", "*there*"));
        assertThat(r.get("content").get("body").asString()).isEqualTo("*Hi* _*there*_ ~x~ ```code```\nline2");
    }

    @Test
    void overflowAfterRenderIs400() throws Exception {
        String id = create(text("Big", "a".repeat(4090) + "{{v}}", setting("v", null, "V", null, false)));
        send(post(url() + "/" + id + "/render"), leadToken, Map.of("values", Map.of("v", "b".repeat(100))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION"))
                .andExpect(jsonPath("$.fields['content.body']")
                        .value("is longer than 4096 characters after filling placeholders"));
        render(leadToken, id, null, Map.of("v", "b".repeat(6)));
    }

    @Test
    void pollOptionsDuplicateAfterRenderIs400() throws Exception {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("question", "Pick");
        content.put("options", List.of("{{a}}", "{{b}}"));
        String id = create(request("Poll", "POLL", content,
                setting("a", null, "A", null, false), setting("b", null, "B", null, false)));
        send(post(url() + "/" + id + "/render"), leadToken, Map.of("values", Map.of("a", "Red", "b", "red")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields['content.options']").exists());
        JsonNode ok = render(leadToken, id, null, Map.of("a", "Red", "b", "Blue"));
        assertThat(ok.get("content").get("options").get(0).asString()).isEqualTo("Red");
        assertThat(ok.get("content").get("options").get(1).asString()).isEqualTo("Blue");
        assertThat(ok.get("content").get("question").asString()).isEqualTo("Pick");
    }

    @Test
    void contactCardHasVcard() throws Exception {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("fullName", "{{who}}");
        content.put("phone", "919876543210");
        content.put("organization", "Acme");
        content.put("email", "a@b.com");
        String id = create(request("Card", "CONTACT_CARD", content, setting("who", null, "Who", null, true)));
        JsonNode r = render(leadToken, id, null, Map.of("who", "Doe, John; Jr\nX\\Y"));
        assertThat(r.get("content").get("fullName").asString()).isEqualTo("Doe, John; Jr\nX\\Y");
        assertThat(r.get("vcard").asString()).isEqualTo("BEGIN:VCARD\r\nVERSION:3.0\r\n"
                + "FN:Doe\\, John\\; Jr\\nX\\\\Y\r\nORG:Acme\r\n"
                + "TEL;type=CELL;waid=919876543210:+919876543210\r\nEMAIL:a@b.com\r\nEND:VCARD\r\n");
    }

    @Test
    void renderWithOtherBrandContactIs404() throws Exception {
        String id = greeting();
        Contact other = new Contact();
        other.setWorkspaceId(brandB.getId());
        other.setCustomerId(acme.getId());
        other.setName("Zed");
        other.setPhone("919800000000");
        other = contacts.save(other);
        rawRender(leadToken, id, other.getId(), Map.of()).andExpect(status().isNotFound());
        rawRender(leadToken, id, "nope", Map.of()).andExpect(status().isNotFound());
        rawRender(leadToken, "nope", null, Map.of()).andExpect(status().isNotFound());
    }

    @Test
    void renderWithContactNeedsContactView() throws Exception {
        String id = greeting();
        Contact c = contact(Map.of(), "Asha", "919876543210");
        Role r = new Role();
        r.setName("TemplateViewer");
        r.setLevel(RoleLevel.BRAND);
        r.setAuthorities(Set.of("template:view"));
        r = roles.save(r);
        roleCache.evictAll();
        String token = testUsers.accessToken(testUsers.createInCustomer(acme.getId(), "viewer", null,
                List.of(new WorkspaceRole(brandA.getId(), r.getId()))));
        rawRender(token, id, c.getId(), Map.of()).andExpect(status().isForbidden());
        rawRender(token, id, null, Map.of()).andExpect(status().isOk());
    }

    @Test
    void agentCanRender() throws Exception {
        String id = greeting();
        Contact c = contact(Map.of(), "Asha", "919876543210");
        JsonNode r = render(agentToken, id, c.getId(), Map.of());
        assertThat(r.get("content").get("body").asString()).isEqualTo("Hi Asha from your city on +919876543210");
    }

    @Test
    void mediaIsReturnedAsView() throws Exception {
        byte[] png = new byte[40];
        byte[] sig = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
        System.arraycopy(sig, 0, png, 0, sig.length);
        String mediaId = json(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .multipart(base(brandA) + "/template-media")
                .file(new org.springframework.mock.web.MockMultipartFile("file", "logo.png",
                        "application/octet-stream", png))
                .header("Authorization", "Bearer " + leadToken)).andExpect(status().isCreated()))
                .get("mediaId").asString();
        Map<String, Object> req = request("Pic", "IMAGE", new LinkedHashMap<>(Map.of("caption", "Hi {{contact.name}}")),
                setting("contact.name", "friend", null, null, null));
        req.put("mediaId", mediaId);
        String id = create(req);
        JsonNode r = render(leadToken, id, null, Map.of());
        assertThat(r.get("type").asString()).isEqualTo("IMAGE");
        assertThat(r.get("content").get("caption").asString()).isEqualTo("Hi friend");
        assertThat(r.get("media").get("mediaId").asString()).isEqualTo(mediaId);
        assertThat(r.get("media").get("fileName").asString()).isEqualTo("logo.png");
        assertThat(r.get("media").get("mimeType").asString()).isEqualTo("image/png");
        assertThat(r.get("media").get("size").asLong()).isEqualTo(40);
    }

    private MessageTemplate legacy(String name, String body, TemplatePlaceholder... ps) {
        MessageTemplate t = new MessageTemplate();
        t.setWorkspaceId(brandA.getId());
        t.setCustomerId(acme.getId());
        t.setName(name);
        t.setNameKey(name.toLowerCase());
        t.setType(TemplateType.TEXT);
        TemplateContent c = new TemplateContent();
        c.setBody(body);
        t.setContent(c);
        t.setPlaceholders(new ArrayList<>(List.of(ps)));
        t.setStatus(TemplateStatus.ACTIVE);
        return templates.save(t);
    }

    private static TemplatePlaceholder agent(String name) {
        TemplatePlaceholder p = new TemplatePlaceholder();
        p.setName(name);
        p.setKind(PlaceholderKind.AGENT);
        p.setLabel("Name");
        p.setFallback("x");
        return p;
    }

    @Test
    void storedThisPlaceholderNeverDumpsTheContext() throws Exception {
        MessageTemplate t = legacy("LegacyA", "Hi {{this}}", agent("this"));
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> rawRender(leadToken, t.getId(), null, Map.of("this", "Ravi")))
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("template uses the reserved name \"this\"");

        TemplatePlaceholder attr = new TemplatePlaceholder();
        attr.setName("contact.attributes.this");
        attr.setKind(PlaceholderKind.CONTACT_ATTRIBUTE);
        attr.setAttributeKey("this");
        attr.setFallback("x");
        MessageTemplate saved = legacy("LegacyB", "Hi {{contact.attributes.this}}", attr);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> rawRender(leadToken, saved.getId(), null, Map.of()))
                .hasRootCauseInstanceOf(IllegalStateException.class);
    }

    @Test
    void blankBodyAfterRenderingIs400() throws Exception {
        String id = create(text("Blank", "{{note}}", setting("note", null, "Note", null, false)));
        send(post(url() + "/" + id + "/render"), leadToken, Map.of("values", Map.of("note", "  ")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION"))
                .andExpect(jsonPath("$.fields['content.body']").value("is empty after filling placeholders"));
        rawRender(leadToken, id, null, null).andExpect(status().isBadRequest());
        JsonNode ok = render(leadToken, id, null, Map.of("note", "hello"));
        assertThat(ok.get("content").get("body").asString()).isEqualTo("hello");
    }

    @Test
    void blankCardNameQuestionAndOptionAfterRenderingAre400() throws Exception {
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("fullName", "{{who}}");
        card.put("phone", "919876543210");
        String cardId = create(request("Card2", "CONTACT_CARD", card, setting("who", null, "Who", null, false)));
        rawRender(leadToken, cardId, null, Map.of()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields['content.fullName']").value("is empty after filling placeholders"));

        Map<String, Object> poll = new LinkedHashMap<>();
        poll.put("question", "{{q}}");
        poll.put("options", List.of("{{a}}", "Blue"));
        String pollId = create(request("Poll2", "POLL", poll,
                setting("q", null, "Q", null, false), setting("a", null, "A", null, false)));
        rawRender(leadToken, pollId, null, Map.of()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields['content.question']").value("is empty after filling placeholders"))
                .andExpect(jsonPath("$.fields['content.options']").value("is empty after filling placeholders"));
        render(leadToken, pollId, null, Map.of("q", "Pick", "a", "Red"));
    }

    @Test
    void campaignRenderTakesEveryPlaceholderFromValues() throws Exception {
        attribute("City", "city");
        String id = create(text("Offer", "Hi {{contact.name}} from {{contact.attributes.city}}, {{offer}}",
                setting("contact.name", "there", null, null, null),
                setting(CITY, "your city", null, null, null),
                setting("offer", null, "Offer", null, true)));
        SecurityContextHolder.clearContext();

        RenderedTemplate r = renderer.renderForCampaign(brandA.getId(), id,
                Map.of("contact.name", "Asha", CITY, "Pune", "offer", "10% off"));

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(r.content().getBody()).isEqualTo("Hi Asha from Pune, 10% off");
        assertThat(r.missing()).isEmpty();
    }

    @Test
    void campaignRenderUsesFallbacksAndNeverReportsMissing() throws Exception {
        String id = create(text("Ref", "Hi {{contact.name}}, ref [{{ref}}]",
                setting("contact.name", "friend", null, null, null),
                setting("ref", null, "Ref", "A-1", true)));
        SecurityContextHolder.clearContext();

        RenderedTemplate blank = renderer.renderForCampaign(brandA.getId(), id, Map.of("ref", "   "));
        assertThat(blank.content().getBody()).isEqualTo("Hi friend, ref []");
        assertThat(blank.missing()).isEmpty();

        RenderedTemplate cut = renderer.renderForCampaign(brandA.getId(), id,
                Map.of("contact.name", "x".repeat(1200), "ref", " R "));
        assertThat(cut.content().getBody()).isEqualTo("Hi " + "x".repeat(1000) + ", ref [R]");
        assertThat(cut.missing()).isEmpty();
    }
}
