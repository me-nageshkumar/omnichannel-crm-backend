package me.nageshk.omnichannel.app.inbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import me.nageshk.omnichannel.app.support.FakeDevice;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import me.nageshk.omnichannel.db.inbox.WaWebMessage;
import me.nageshk.omnichannel.db.page.MessagePage;
import me.nageshk.omnichannel.db.page.MessagePageRepository;
import me.nageshk.omnichannel.security.access.AccessService;
import me.nageshk.omnichannel.service.cloud.CloudMediaService;
import me.nageshk.omnichannel.service.rpc.Envelope;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Spec §5.6–5.7: Meta's own request bodies go through the bridge; interactive ones go out as a page link. */
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "omnichannel.rpc.timeout=2s")
class CloudSendApiIT extends InboxApiTestBase {

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired MessagePageRepository pages;
    @Autowired CloudMediaService media;
    @Autowired AccessService access;

    record Exchange(ResponseEntity<JsonNode> response, Envelope request) {
    }

    static final String LINK = "^http://pages\\.test/t/[A-Za-z0-9_-]{22}$";

    private String numberUrl() {
        return "http://localhost:" + port + "/api/workspaces/" + brand.getId() + "/channel-accounts/" + number.getId()
                + "/messages";
    }

    private String chatUrl(WaWebChat chat) {
        return "http://localhost:" + port + inbox() + "/chats/" + chat.getId() + "/cloud-messages";
    }

    private ResponseEntity<JsonNode> post(String token, String url, String json) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(url, HttpMethod.POST, new HttpEntity<>(json, h), JsonNode.class);
    }

    private ObjectNode ready() {
        return mapper.createObjectNode().put("pushname", "N").put("wid", number.getNumber() + "@c.us");
    }

    private ObjectNode model(String remoteId, String serialized, String body) {
        ObjectNode m = mapper.createObjectNode();
        m.set("id", mapper.createObjectNode().put("_serialized", serialized).put("fromMe", true).put("remote", remoteId));
        m.put("fromMe", true).put("body", body).put("type", "chat").put("ack", 1)
                .put("timestamp", Instant.now().getEpochSecond());
        return m;
    }

    /** Posts with a connected device; a {@code getNumberId} first is answered as registered, then the send. */
    private Exchange viaDevice(String token, String url, String json, Function<Envelope, Envelope> answer) throws Exception {
        try (FakeDevice device = FakeDevice.connectAndAuth(port, testDevices.redeem(number).deviceKey())) {
            device.send(Envelope.event("ready", ready()));
            Thread.sleep(200);
            CompletableFuture<ResponseEntity<JsonNode>> http = CompletableFuture.supplyAsync(() -> post(token, url, json));
            Envelope req = device.next();
            if ("getNumberId".equals(req.method())) {
                String phone = req.params().get("phone").asString();
                ObjectNode lookup = mapper.createObjectNode().put("phone", phone + "@c.us");
                lookup.putNull("lid");
                device.send(Envelope.response(req.id(), lookup));
                req = device.next();
            }
            assertThat(req.method()).isEqualTo("sendMessage");
            device.send(answer.apply(req));
            return new Exchange(http.get(5, TimeUnit.SECONDS), req);
        }
    }

    private Exchange sendOk(String token, String url, String json, String remoteId) throws Exception {
        return viaDevice(token, url, json, req -> Envelope.response(req.id(),
                model(remoteId, "true_" + remoteId + "_" + System.nanoTime(), req.params().get("content").asString())));
    }

    private WaWebChat chatWithAarav() {
        WaWebChat c = chat("Aarav", "919812345678", 5);
        c.setRemoteId("919812345678@c.us");
        return chats.save(c);
    }

    private WaWebMessage only(WaWebChat chat) {
        List<WaWebMessage> all = messages.findAll().stream().filter(m -> chat.getId().equals(m.getChatId())).toList();
        assertThat(all).hasSize(1);
        return all.get(0);
    }

    static String text(String to, String body) {
        return "{\"messaging_product\":\"whatsapp-web\",\"recipient_type\":\"individual\",\"to\":\"" + to
                + "\",\"type\":\"text\",\"text\":{\"preview_url\":true,\"body\":\"" + body + "\"}}";
    }

    static String button(String to, String header) {
        return "{\"messaging_product\":\"whatsapp-web\",\"recipient_type\":\"individual\"" + (to == null ? "" : ",\"to\":\"" + to + "\"")
                + ",\"type\":\"interactive\",\"interactive\":{\"type\":\"button\"," + (header == null ? "" : header + ",")
                + "\"body\":{\"text\":\"Hi Pablo! Tap a button.\"},\"footer\":{\"text\":\"Lucky Shrub\"},"
                + "\"action\":{\"buttons\":[{\"type\":\"reply\",\"reply\":{\"id\":\"change-button\",\"title\":\"Change\"}},"
                + "{\"type\":\"reply\",\"reply\":{\"id\":\"cancel-button\",\"title\":\"Cancel\"}}]}}}";
    }

    @Test
    void textGoesOutAsText() throws Exception {
        WaWebChat chat = chatWithAarav();
        Exchange ex = sendOk(managerToken, numberUrl(), text("919812345678", "hi"), chat.getRemoteId());
        assertThat(ex.response().getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ex.request().params().get("content").asString()).isEqualTo("hi");
        assertThat(ex.request().params().get("options").get("linkPreview").asBoolean()).isTrue();
        JsonNode body = ex.response().getBody();
        assertThat(body.get("messaging_product").asString()).isEqualTo("whatsapp-web");
        assertThat(body.get("contacts").get(0).get("wa_id").asString()).isEqualTo("919812345678");
        WaWebMessage stored = only(chat);
        assertThat(body.get("messages").get(0).get("id").asString()).isEqualTo(stored.getId());
        assertThat(stored.getRequest()).containsEntry("type", "text").containsEntry("messaging_product", "whatsapp-web");
        assertThat(stored.getMessagePageId()).isNull();
    }

    @Test
    void buttonGoesOutAsALinkWithPreview() throws Exception {
        WaWebChat chat = chatWithAarav();
        Exchange ex = sendOk(managerToken, numberUrl(), button("919812345678", null), chat.getRemoteId());
        assertThat(ex.response().getStatusCode()).isEqualTo(HttpStatus.OK);
        String content = ex.request().params().get("content").asString();
        assertThat(content).matches(LINK);
        assertThat(ex.request().params().get("options").get("linkPreview").asBoolean()).isTrue();
        WaWebMessage stored = only(chat);
        assertThat(stored.getBody()).isEqualTo(content);
        assertThat(stored.getType()).isEqualTo("text");
        assertThat(stored.getMessagePageId()).isNotNull();
        assertThat(((Map<?, ?>) stored.getRequest().get("interactive")).get("type")).isEqualTo("button");
        MessagePage page = pages.findById(stored.getMessagePageId()).orElseThrow();
        assertThat(page.getMessageId()).isEqualTo(stored.getId());
        assertThat(page.getChatId()).isEqualTo(chat.getId());
        assertThat(page.getContactId()).isEqualTo(chat.getContactId());
        assertThat(page.isPreviewReady()).isTrue();
        assertThat(content).endsWith("/t/" + page.getToken());
        ResponseEntity<byte[]> png = rest.getForEntity("http://localhost:" + port + "/t/" + page.getToken() + "/og.png", byte[].class);
        assertThat(png.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(png.getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_PNG);
        assertThat(ex.response().getBody().get("messages").get(0).get("id").asString()).isEqualTo(stored.getId());
    }

    @Test
    void chatKeyedRouteFillsTo() throws Exception {
        WaWebChat chat = chatWithAarav();
        Exchange ex = sendOk(managerToken, chatUrl(chat), button(null, null), chat.getRemoteId());
        assertThat(ex.response().getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(only(chat).getRequest()).containsEntry("to", "919812345678");

        WaWebChat lid = chat("Hidden", "919800000011", 3);
        lid.setRemoteId("103238296682612@lid");
        lid.setContactId(null);
        lid = chats.save(lid);
        Exchange ex2 = sendOk(managerToken, chatUrl(lid), button(null, null), lid.getRemoteId());
        assertThat(ex2.response().getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(only(lid).getRequest()).containsEntry("to", "103238296682612@lid");
    }

    @Test
    void whatsappProductIsRejected() {
        chatWithAarav();
        ResponseEntity<JsonNode> r = post(managerToken, numberUrl(), text("919812345678", "hi").replace("whatsapp-web", "whatsapp"));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(r.getBody().get("fields").get("messaging_product").asString()).isEqualTo("channel not available");
    }

    @Test
    void linkHeaderAndUnknownMediaAreRejected() {
        chatWithAarav();
        ResponseEntity<JsonNode> link = post(managerToken, numberUrl(),
                button("919812345678", "\"header\":{\"type\":\"image\",\"image\":{\"link\":\"https://x/y.png\"}}"));
        assertThat(link.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(link.getBody().get("fields").has("interactive.header.image.link")).isTrue();
        ResponseEntity<JsonNode> unknown = post(managerToken, numberUrl(),
                button("919812345678", "\"header\":{\"type\":\"image\",\"image\":{\"id\":\"6ad7380562bdf17ec6a0a2ed\"}}"));
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(unknown.getBody().get("fields").get("interactive.header.image.id").asString()).isEqualTo("unknown media");
        assertThat(pages.count()).isZero();
    }

    @Test
    void unknownKeyIsRejected() {
        chatWithAarav();
        ResponseEntity<JsonNode> r = post(managerToken, numberUrl(), text("919812345678", "hi").replace("\"type\":\"text\"", "\"type\":\"text\",\"extra\":1"));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(r.getBody().get("fields").get("message").asString()).isEqualTo("unknown field extra");
    }

    @Test
    void newNumberStartsAChat() throws Exception {
        Exchange ex = sendOk(managerToken, numberUrl(), button("919700000001", null), "919700000001@c.us");
        assertThat(ex.response().getStatusCode()).isEqualTo(HttpStatus.OK);
        WaWebChat created = chats.findByPhoneNumberIdAndRemoteId(number.getId(), "919700000001@c.us").orElseThrow();
        assertThat(created.getAssignedUserId()).isEqualTo(manager.getId());
        assertThat(only(created).getMessagePageId()).isNotNull();
    }

    @Test
    void previewReturnsInertHtml() throws Exception {
        CloudMediaService.Stored s = media.upload(access.load(manager), brand.getId(), "image",
                new MockMultipartFile("file", "a.png", "application/octet-stream", CloudMediaIT.png(100)));
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(managerToken);
        h.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> r = rest.exchange(numberUrl() + "/preview", HttpMethod.POST,
                new HttpEntity<>(button(null, "\"header\":{\"type\":\"image\",\"image\":{\"id\":\"" + s.id() + "\"}}"), h), String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getHeaders().getContentType().isCompatibleWith(MediaType.TEXT_HTML)).isTrue();
        assertThat(r.getBody()).contains("Hi Pablo! Tap a button.").contains("data-k=\"reply\" href=\"#\"")
                .doesNotContain("/t/preview/e").contains("+" + number.getNumber())
                // the iframe is sandboxed and cannot send a bearer, so the header image travels inline
                .contains("src=\"data:image/png;base64,").doesNotContain("/t/preview/m");
        assertThat(pages.count()).isZero();
    }

    @Test
    void refusedSendLeavesNoMessage() throws Exception {
        WaWebChat chat = chatWithAarav();
        Exchange ex = viaDevice(managerToken, numberUrl(), button("919812345678", null),
                req -> Envelope.errorResponse(req.id(), "PAGE_ERROR", "boom"));
        assertThat(ex.response().getStatusCode().isError()).isTrue();
        assertThat(ex.response().getBody().get("code").asString()).isEqualTo("PAGE_ERROR");
        assertThat(messages.findAll().stream().filter(m -> chat.getId().equals(m.getChatId()))).isEmpty();
        List<MessagePage> orphans = pages.findAll();
        assertThat(orphans).hasSize(1);
        assertThat(orphans.get(0).getMessageId()).isNull();
        ResponseEntity<String> page = rest.getForEntity("http://localhost:" + port + "/t/" + orphans.get(0).getToken(), String.class);
        assertThat(page.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ---- media and location types (chatbot spec §4.2) ----

    private String uploadPng() {
        return media.upload(access.load(manager), brand.getId(), "image",
                new MockMultipartFile("file", "a.png", "application/octet-stream", CloudMediaIT.png(100))).id();
    }

    @Test
    void imageIsSentNatively() throws Exception {
        WaWebChat chat = chatWithAarav();
        String id = uploadPng();
        String json = "{\"messaging_product\":\"whatsapp-web\",\"recipient_type\":\"individual\",\"to\":\"919812345678\","
                + "\"type\":\"image\",\"image\":{\"id\":\"" + id + "\",\"caption\":\"Our catalogue\"}}";
        Exchange ex = sendOk(managerToken, numberUrl(), json, chat.getRemoteId());
        assertThat(ex.response().getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode options = ex.request().params().get("options");
        assertThat(options.get("media").get("mimetype").asString()).isEqualTo("image/png");
        assertThat(options.get("media").get("data").asString()).isNotBlank();
        assertThat(options.get("caption").asString()).isEqualTo("Our catalogue");
        WaWebMessage stored = only(chat);
        assertThat(stored.getRequest()).containsEntry("type", "image");
        assertThat(stored.getMedia().mimetype()).isEqualTo("image/png");
        assertThat(stored.getMessagePageId()).isNull();
        assertThat(pages.count()).isZero();
    }

    @Test
    void locationIsSentNatively() throws Exception {
        WaWebChat chat = chatWithAarav();
        String json = "{\"messaging_product\":\"whatsapp-web\",\"recipient_type\":\"individual\",\"to\":\"919812345678\","
                + "\"type\":\"location\",\"location\":{\"latitude\":12.9716,\"longitude\":77.5946,\"name\":\"Our store\",\"address\":\"MG Road\"}}";
        Exchange ex = sendOk(managerToken, numberUrl(), json, chat.getRemoteId());
        assertThat(ex.response().getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode location = ex.request().params().get("options").get("location");
        assertThat(location.get("latitude").asDouble()).isEqualTo(12.9716);
        assertThat(location.get("description").asString()).isEqualTo("Our store, MG Road");
        WaWebMessage stored = only(chat);
        assertThat(stored.getLocation().latitude()).isEqualTo(12.9716);
        assertThat(stored.getRequest()).containsEntry("type", "location");
    }
}
