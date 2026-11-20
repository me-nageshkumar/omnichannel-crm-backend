package me.nageshk.omnichannel.app.meta;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import me.nageshk.omnichannel.db.inbox.WaWebMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import tools.jackson.databind.JsonNode;

/** Meta templates: imported, kept current by Meta's webhook, filled and sent (spec 2026-11-20 §5.6). */
class CloudTemplatesIT extends MetaTestBase {

    static final String FOLLOW_UP = "{\"id\":\"t1\",\"name\":\"course_follow_up\",\"language\":\"en\",\"status\":\"APPROVED\","
            + "\"category\":\"UTILITY\",\"parameter_format\":\"POSITIONAL\",\"components\":[{\"type\":\"BODY\","
            + "\"text\":\"Hi {{1}}, your {{2}} admission is open\",\"example\":{\"body_text\":[[\"Veenu\",\"BCA\"]]}},"
            + "{\"type\":\"FOOTER\",\"text\":\"Bimal Institute\"}]}";
    static final String BROCHURE = "{\"id\":\"t2\",\"name\":\"brochure\",\"language\":\"en\",\"status\":\"APPROVED\","
            + "\"category\":\"MARKETING\",\"parameter_format\":\"POSITIONAL\",\"components\":[{\"type\":\"HEADER\","
            + "\"format\":\"IMAGE\"},{\"type\":\"BODY\",\"text\":\"Our brochure\"}]}";
    static final String PENDING = "{\"id\":\"t3\",\"name\":\"fees_reminder\",\"language\":\"en\",\"status\":\"PENDING\","
            + "\"category\":\"UTILITY\",\"components\":[{\"type\":\"BODY\",\"text\":\"Fees due\"}]}";
    static final String CAROUSEL = "{\"id\":\"t4\",\"name\":\"courses\",\"language\":\"en\",\"status\":\"APPROVED\","
            + "\"category\":\"MARKETING\",\"components\":[{\"type\":\"BODY\",\"text\":\"Courses\"},{\"type\":\"CAROUSEL\","
            + "\"cards\":[]}]}";

    private String connectionId;
    private String key;
    private String numberId;
    private WaWebChat chat;

    @BeforeEach
    void cloudChat() throws Exception {
        mongo.remove(new Query(), "channelWebhookEvents");
        JsonNode connection = connect("111");
        connectionId = connection.get("id").asString();
        key = keyOf(connection);
        numberId = addCloudNumber(connectionId, "Admissions", "333", "+91 98765 43210").get("id").asString();
        webhook(key, envelope(textChange("333", "919876543210", "919799750743", "wamid.IN1", "Admission enquiry")));
        await(() -> !messages().isEmpty());
        chat = chats.findById(messages().get(0).getChatId()).orElseThrow();
        GRAPH.reset();
    }

    private List<WaWebMessage> messages() {
        return mongo.find(new Query(Criteria.where("phoneNumberId").is(numberId)), WaWebMessage.class);
    }

    private void sync(String... templates) {
        GRAPH.enqueue("GET /222/message_templates", 200, "{\"data\":[" + String.join(",", templates) + "]}");
        ResponseEntity<JsonNode> res = meta(HttpMethod.POST, "/templates/sync", manager, Map.of());
        assertThat(res.getStatusCode()).as(String.valueOf(res.getBody())).isEqualTo(HttpStatus.OK);
    }

    private JsonNode list(boolean sendable) {
        return meta(HttpMethod.GET, "/templates?phoneNumberId=" + numberId + "&sendable=" + sendable, manager, null)
                .getBody();
    }

    private String idOf(String name) {
        for (JsonNode t : list(false)) if (name.equals(t.get("name").asString())) return t.get("id").asString();
        throw new AssertionError("no template " + name);
    }

    private ResponseEntity<JsonNode> sendTemplate(String id, Map<String, String> values, String headerMediaId) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("cloudTemplateId", id);
        body.put("values", values);
        if (headerMediaId != null) body.put("headerMediaId", headerMediaId);
        return rest.exchange(url("/api/workspaces/" + brand.getId() + "/inbox/chats/" + chat.getId() + "/cloud-templates"),
                HttpMethod.POST, auth(manager, body), JsonNode.class);
    }

    private static String sent(String wamid) {
        return "{\"messaging_product\":\"whatsapp\",\"messages\":[{\"id\":\"" + wamid + "\"}]}";
    }

    @Test
    void syncImportsAllPages() {
        GRAPH.enqueue("GET /222/message_templates", 200, "{\"data\":[" + FOLLOW_UP + "," + BROCHURE + "],\"paging\":"
                + "{\"next\":\"" + GRAPH.baseUrl() + "/v27.0/222/message_templates?after=X\"}}");
        GRAPH.enqueue("GET /222/message_templates", 200, "{\"data\":[" + PENDING + "]}");

        JsonNode res = meta(HttpMethod.POST, "/templates/sync", manager, Map.of()).getBody();

        assertThat(res.get("added").asInt()).isEqualTo(3);
        assertThat(list(false)).hasSize(3);
        JsonNode followUp = null;
        for (JsonNode t : list(false)) if ("course_follow_up".equals(t.get("name").asString())) followUp = t;
        assertThat(followUp.get("variables").get(0).get("key").asString()).isEqualTo("body.1");

        sync(FOLLOW_UP, BROCHURE);

        JsonNode pending = null;
        for (JsonNode t : list(false)) if ("fees_reminder".equals(t.get("name").asString())) pending = t;
        assertThat(pending.get("status").asString()).isEqualTo("DELETED");
    }

    @Test
    void statusWebhookUpdatesRow() throws Exception {
        sync(FOLLOW_UP);

        webhook(key, envelope("{\"field\":\"message_template_status_update\",\"value\":{\"event\":\"REJECTED\","
                + "\"message_template_id\":\"t1\",\"message_template_name\":\"course_follow_up\","
                + "\"message_template_language\":\"en\",\"reason\":\"INCORRECT_CATEGORY\"}}"));

        await(() -> "REJECTED".equals(list(false).get(0).get("status").asString()));
        assertThat(list(false).get(0).get("rejectedReason").asString()).isEqualTo("INCORRECT_CATEGORY");

        GRAPH.enqueue("GET /222/message_templates", 200, "{\"data\":[" + FOLLOW_UP + "," + PENDING + "]}");
        webhook(key, envelope("{\"field\":\"message_template_status_update\",\"value\":{\"event\":\"APPROVED\","
                + "\"message_template_id\":9999,\"message_template_name\":\"new_one\",\"message_template_language\":\"en\"}}"));
        await(() -> !GRAPH.recorded("GET /222/message_templates").isEmpty());
    }

    @Test
    void onlyApprovedSupportedAreSendable() {
        sync(FOLLOW_UP, BROCHURE, PENDING, CAROUSEL);

        assertThat(list(true)).extracting(t -> t.get("name").asString()).containsExactlyInAnyOrder("course_follow_up",
                "brochure");
        JsonNode carousel = null;
        for (JsonNode t : list(false)) if ("courses".equals(t.get("name").asString())) carousel = t;
        assertThat(carousel.get("supported").asBoolean()).isFalse();
        assertThat(carousel.get("unsupportedReason").asString()).contains("CAROUSEL");
        ResponseEntity<JsonNode> agentView = meta(HttpMethod.GET, "/templates?phoneNumberId=" + numberId
                + "&sendable=true", agent, null);
        assertThat(agentView.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void fillsPreFillFromContact() throws Exception {
        sync(FOLLOW_UP);
        String id = idOf("course_follow_up");
        ResponseEntity<JsonNode> saved = meta(HttpMethod.PUT, "/templates/" + id + "/fills", manager, Map.of("fills",
                Map.of("body.1", Map.of("kind", "CONTACT_FIELD", "attributeKey", "name"))));
        assertThat(saved.getStatusCode()).isEqualTo(HttpStatus.OK);
        GRAPH.enqueue("POST /333/messages", 200, sent("wamid.T1"));

        ResponseEntity<JsonNode> res = sendTemplate(id, Map.of("body.2", "BCA"), null);

        assertThat(res.getStatusCode()).as(String.valueOf(res.getBody())).isEqualTo(HttpStatus.OK);
        JsonNode body = mapper.readTree(GRAPH.recorded("POST /333/messages").get(0).body());
        assertThat(body.get("type").asString()).isEqualTo("template");
        assertThat(body.get("template").get("name").asString()).isEqualTo("course_follow_up");
        assertThat(body.get("template").get("language").get("code").asString()).isEqualTo("en");
        JsonNode params = body.get("template").get("components").get(0).get("parameters");
        assertThat(params.get(0).get("text").asString()).isEqualTo("Veenu");
        assertThat(params.get(1).get("text").asString()).isEqualTo("BCA");
        assertThat(res.getBody().get("body").asString()).isEqualTo("Hi Veenu, your BCA admission is open");
        assertThat(res.getBody().get("template").get("footer").asString()).isEqualTo("Bimal Institute");
        WaWebMessage row = messages().stream().filter(m -> "wamid.T1".equals(m.getWaMessageId())).findFirst().orElseThrow();
        assertThat(row.getCloudTemplateId()).isEqualTo(id);
        assertThat(list(false).get(0).get("lastUsedAt").isNull()).isFalse();
    }

    @Test
    void givenValueWinsOverFill() throws Exception {
        sync(FOLLOW_UP);
        String id = idOf("course_follow_up");
        meta(HttpMethod.PUT, "/templates/" + id + "/fills", manager, Map.of("fills",
                Map.of("body.1", Map.of("kind", "CONTACT_FIELD", "attributeKey", "name"))));
        GRAPH.enqueue("POST /333/messages", 200, sent("wamid.T2"));

        sendTemplate(id, Map.of("body.1", "Veenu ji", "body.2", "MBA"), null);

        JsonNode params = mapper.readTree(GRAPH.recorded("POST /333/messages").get(0).body()).get("template")
                .get("components").get(0).get("parameters");
        assertThat(params.get(0).get("text").asString()).isEqualTo("Veenu ji");
    }

    @Test
    void missingValueIs400() {
        sync(FOLLOW_UP);

        ResponseEntity<JsonNode> res = sendTemplate(idOf("course_follow_up"), Map.of("body.1", "Veenu"), null);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().get("fields").has("values.body.2")).isTrue();
        assertThat(GRAPH.recorded("POST /333/messages")).isEmpty();
    }

    @Test
    void notSendableTemplateIs400() {
        sync(PENDING);

        assertThat(sendTemplate(idOf("fees_reminder"), Map.of(), null).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void headerImageTemplateNeedsFile() throws Exception {
        sync(BROCHURE);
        String id = idOf("brochure");

        ResponseEntity<JsonNode> without = sendTemplate(id, Map.of(), null);
        assertThat(without.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(without.getBody().get("fields").has("headerMediaId")).isTrue();

        byte[] png = new byte[100];
        System.arraycopy(new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A}, 0, png, 0, 8);
        String mediaId = uploadCloudMedia("brochure.png", "image/png", png);
        GRAPH.enqueue("POST /333/media", 200, "{\"id\":\"meta-media-1\"}");
        GRAPH.enqueue("POST /333/messages", 200, sent("wamid.B1"));

        ResponseEntity<JsonNode> with = sendTemplate(id, Map.of(), mediaId);

        assertThat(with.getStatusCode()).as(String.valueOf(with.getBody())).isEqualTo(HttpStatus.OK);
        JsonNode header = mapper.readTree(GRAPH.recorded("POST /333/messages").get(0).body()).get("template")
                .get("components").get(0);
        assertThat(header.get("type").asString()).isEqualTo("header");
        assertThat(header.get("parameters").get(0).get("image").get("id").asString()).isEqualTo("meta-media-1");
    }

    @Test
    void newChatOnCloudSendsTemplate() throws Exception {
        sync(FOLLOW_UP);
        GRAPH.enqueue("POST /333/messages", 200, sent("wamid.N1"));

        ResponseEntity<JsonNode> res = rest.exchange(url("/api/workspaces/" + brand.getId() + "/inbox/phone-numbers/"
                + numberId + "/chats"), HttpMethod.POST, auth(manager, Map.of("phone", "919811122233", "cloudTemplate",
                        Map.of("cloudTemplateId", idOf("course_follow_up"), "values", Map.of("body.1", "Ravi",
                                "body.2", "BBA")))), JsonNode.class);

        assertThat(res.getStatusCode()).as(String.valueOf(res.getBody())).isEqualTo(HttpStatus.OK);
        JsonNode body = mapper.readTree(GRAPH.recorded("POST /333/messages").get(0).body());
        assertThat(body.get("to").asString()).isEqualTo("919811122233");
        assertThat(body.get("type").asString()).isEqualTo("template");
        assertThat(chats.findByPhoneNumberIdAndRemoteId(numberId, "919811122233@c.us")).isPresent();
    }

    @Test
    void bridgeRefusesTemplateType() {
        ResponseEntity<JsonNode> res = rest.exchange(url("/api/workspaces/" + brand.getId() + "/channel-accounts/"
                + n1.getId() + "/messages"), HttpMethod.POST, auth(manager, Map.of("messaging_product", "whatsapp-web",
                        "to", "919799750743", "type", "template", "template", Map.of("name", "x", "language",
                                Map.of("code", "en")))), JsonNode.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().toString()).contains("channel not available");
    }

    /** Uploads a file to the brand's Cloud media (the composer's upload) and returns its id. */
    private String uploadCloudMedia(String filename, String mime, byte[] bytes) {
        HttpHeaders fileHeaders = new HttpHeaders();
        fileHeaders.setContentType(MediaType.parseMediaType(mime));
        fileHeaders.setContentDispositionFormData("file", filename);
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", new HttpEntity<>(new org.springframework.core.io.ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return filename;
            }
        }, fileHeaders));
        form.add("messaging_product", "whatsapp");
        form.add("type", mime.substring(0, mime.indexOf('/')));
        HttpHeaders headers = auth(manager, null).getHeaders();
        HttpHeaders h = new HttpHeaders();
        h.putAll(headers);
        h.setContentType(MediaType.MULTIPART_FORM_DATA);
        ResponseEntity<JsonNode> res = rest.exchange(url("/api/workspaces/" + brand.getId() + "/channel-accounts/"
                + numberId + "/media"), HttpMethod.POST, new HttpEntity<>(form, h), JsonNode.class);
        assertThat(res.getStatusCode().is2xxSuccessful()).as(String.valueOf(res.getBody())).isTrue();
        return res.getBody().get("id").asString();
    }
}
