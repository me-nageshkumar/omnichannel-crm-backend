package me.nageshk.omnichannel.inbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import me.nageshk.omnichannel.access.AccessService;
import me.nageshk.omnichannel.phone.PhoneNumberService;
import me.nageshk.omnichannel.rpc.Envelope;
import me.nageshk.omnichannel.support.FakeDevice;
import me.nageshk.omnichannel.template.MediaView;
import me.nageshk.omnichannel.template.TemplateContent;
import me.nageshk.omnichannel.template.TemplateMediaService;
import me.nageshk.omnichannel.template.TemplateRequest;
import me.nageshk.omnichannel.template.TemplateService;
import me.nageshk.omnichannel.template.TemplateType;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.gridfs.GridFsTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Inbound media fetched on demand through a {@link FakeDevice}, the media cache, and cleanup on number delete. */
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "omnichannel.rpc.timeout=2s")
class InboxMediaIT extends InboxApiTestBase {

    /** Collects the chats the media cache announces. */
    @org.springframework.boot.test.context.TestConfiguration
    static class Upserts {
        final List<InboxEvents.MessageUpserted> events = new java.util.concurrent.CopyOnWriteArrayList<>();

        @org.springframework.context.event.EventListener
        void on(InboxEvents.MessageUpserted e) {
            events.add(e);
        }
    }

    @Autowired Upserts upserts;
    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired AccessService access;
    @Autowired TemplateService templateService;
    @Autowired TemplateMediaService templateMedia;
    @Autowired PhoneNumberService phoneNumbers;
    @Autowired ChatMediaService chatMedia;
    @Autowired @Qualifier("chatMediaGridFs") GridFsTemplate chatMediaGridFs;

    @AfterEach
    void cleanMedia() {
        chatMediaGridFs.delete(new Query());
    }

    private static byte[] png() {
        byte[] png = new byte[40];
        byte[] sig = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
        System.arraycopy(sig, 0, png, 0, sig.length);
        png[20] = 7;
        return png;
    }

    private WaWebChat chatWithAarav() {
        WaWebChat c = chat("Aarav", "919812345678", 5);
        c.setRemoteId("919812345678@c.us");
        return chats.save(c);
    }

    private WaWebMessage imageMessage(WaWebChat chat) {
        WaWebMessage m = message(chat, 10, "look");
        m.setType("image");
        m.setMedia(new WaWebMessage.Media("image/png", "a.png", 40L, null, null));
        return messages.save(m);
    }

    private String url(WaWebChat chat, WaWebMessage message) {
        return inbox() + "/chats/" + chat.getId() + "/messages/" + message.getId() + "/media";
    }

    private HttpEntity<Void> auth(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return new HttpEntity<>(h);
    }

    private ResponseEntity<byte[]> bytes(String token, WaWebChat chat, WaWebMessage message) {
        return rest.exchange(url(chat, message), HttpMethod.GET, auth(token), byte[].class);
    }

    private ResponseEntity<JsonNode> json(String token, WaWebChat chat, WaWebMessage message) {
        return rest.exchange(url(chat, message), HttpMethod.GET, auth(token), JsonNode.class);
    }

    private ObjectNode ready() {
        return mapper.createObjectNode().put("pushname", "N").put("wid", number.getNumber() + "@c.us");
    }

    private ObjectNode reply(byte[] data, String mimetype, String filename) {
        return mapper.createObjectNode().put("data", Base64.getEncoder().encodeToString(data))
                .put("mimetype", mimetype).put("filename", filename).put("filesize", data.length);
    }

    /** Downloads with a connected device that answers the {@code downloadMedia} request; returns request and reply. */
    private <T> Map.Entry<Envelope, ResponseEntity<T>> viaDevice(java.util.function.Supplier<ResponseEntity<T>> get,
            Function<Envelope, Envelope> answer) throws Exception {
        try (FakeDevice device = FakeDevice.connectAndAuth(port, testDevices.redeem(number).deviceKey())) {
            device.send(Envelope.event("ready", ready()));
            Thread.sleep(200);
            CompletableFuture<ResponseEntity<T>> http = CompletableFuture.supplyAsync(get);
            Envelope req = device.next();
            assertThat(req.method()).isEqualTo("downloadMedia");
            device.send(answer.apply(req));
            return Map.entry(req, http.get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void firstDownloadFetchesFromDeviceAndCaches() throws Exception {
        WaWebChat chat = chatWithAarav();
        WaWebMessage m = imageMessage(chat);

        var ex = viaDevice(() -> bytes(managerToken, chat, m),
                req -> Envelope.response(req.id(), reply(png(), "image/png", "a.png")));

        assertThat(ex.getKey().params().get("messageId").asString()).isEqualTo(m.getWaMessageId());
        ResponseEntity<byte[]> res = ex.getValue();
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).isEqualTo(png());
        assertThat(res.getHeaders().getContentType().toString()).isEqualTo("image/png");
        assertThat(res.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)).startsWith("inline").contains("a.png");
        assertThat(res.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        WaWebMessage stored = messages.findById(m.getId()).orElseThrow();
        assertThat(stored.getMedia().mediaId()).isNotNull();
        assertThat(stored.getMedia().downloadedAt()).isNotNull();
        assertThat(stored.getMedia().mimetype()).isEqualTo("image/png");
    }

    @Test
    void cacheAnnouncesTheChatAsItIsWhenTheDownloadFinishes() throws Exception {
        WaWebChat chat = chat("Aarav", "919812345678", 5, agent, null);
        WaWebMessage m = imageMessage(chat);
        upserts.events.clear();
        try (FakeDevice device = FakeDevice.connectAndAuth(port, testDevices.redeem(number).deviceKey())) {
            device.send(Envelope.event("ready", ready()));
            Thread.sleep(200);
            CompletableFuture<ResponseEntity<byte[]>> http = CompletableFuture.supplyAsync(
                    () -> bytes(managerToken, chat, m));
            Envelope req = device.next();
            // the chat is handed to someone else while the phone is still producing the file
            WaWebChat moved = chats.findById(chat.getId()).orElseThrow();
            moved.setAssignedUserId(agent2.getId());
            chats.save(moved);
            device.send(Envelope.response(req.id(), reply(png(), "image/png", "a.png")));
            assertThat(http.get(5, TimeUnit.SECONDS).getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        assertThat(upserts.events).hasSize(1);
        assertThat(upserts.events.get(0).chat().getAssignedUserId()).isEqualTo(agent2.getId());
    }

    @Test
    void secondDownloadNeedsNoDevice() throws Exception {
        WaWebChat chat = chatWithAarav();
        WaWebMessage m = imageMessage(chat);
        viaDevice(() -> bytes(managerToken, chat, m),
                req -> Envelope.response(req.id(), reply(png(), "image/png", "a.png")));

        ResponseEntity<byte[]> res = bytes(managerToken, chat, m);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).isEqualTo(png());
    }

    @Test
    void replyFillsMissingMimetypeAndFilenameAndOtherTypesAreAttachments() throws Exception {
        WaWebChat chat = chatWithAarav();
        WaWebMessage m = message(chat, 10, null);
        m.setType("document");
        m.setMedia(new WaWebMessage.Media(null, null, null, null, null));
        m = messages.save(m);
        WaWebMessage doc = m;

        var ex = viaDevice(() -> bytes(managerToken, chat, doc),
                req -> Envelope.response(req.id(), reply("%PDF-1.4".getBytes(), "application/pdf", "t.pdf")));

        assertThat(ex.getValue().getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ex.getValue().getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)).startsWith("attachment")
                .contains("t.pdf");
        WaWebMessage.Media media = messages.findById(doc.getId()).orElseThrow().getMedia();
        assertThat(media.mimetype()).isEqualTo("application/pdf");
        assertThat(media.filename()).isEqualTo("t.pdf");
    }

    @Test
    void aLargeFileCrossesTheSocket() throws Exception {
        WaWebChat chat = chatWithAarav();
        WaWebMessage m = imageMessage(chat);
        byte[] big = new byte[3 * 1024 * 1024];
        big[0] = 1;
        big[big.length - 1] = 2;

        var ex = viaDevice(() -> bytes(managerToken, chat, m),
                req -> Envelope.response(req.id(), reply(big, "image/png", "a.png")));

        assertThat(ex.getValue().getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ex.getValue().getBody()).isEqualTo(big);
    }

    @Test
    void svgIsAlwaysAnAttachmentWhateverItsCase() throws Exception {
        WaWebChat chat = chatWithAarav();
        for (String type : List.of("image/svg+xml", "image/SVG+xml")) {
            WaWebMessage m = message(chat, 10, null);
            m.setType("image");
            m.setMedia(new WaWebMessage.Media(type, "x.svg", null, null, null));
            WaWebMessage svg = messages.save(m);

            var ex = viaDevice(() -> bytes(managerToken, chat, svg),
                    req -> Envelope.response(req.id(), reply("<svg/>".getBytes(), type, "x.svg")));

            assertThat(ex.getValue().getStatusCode()).as(type).isEqualTo(HttpStatus.OK);
            assertThat(ex.getValue().getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)).as(type)
                    .startsWith("attachment");
            assertThat(ex.getValue().getHeaders().getContentType().toString()).as(type).isEqualTo("image/svg+xml");
        }
    }

    @Test
    void emptyOrMalformedDataIs410() throws Exception {
        WaWebChat chat = chatWithAarav();
        for (ObjectNode bad : List.of(reply(new byte[0], "image/png", "a.png"),
                mapper.createObjectNode().put("data", "not base64 !!!").put("mimetype", "image/png"))) {
            WaWebMessage m = imageMessage(chat);

            var ex = viaDevice(() -> json(managerToken, chat, m), req -> Envelope.response(req.id(), bad));

            assertThat(ex.getValue().getStatusCode()).as(bad.toString()).isEqualTo(HttpStatus.GONE);
            assertThat(messages.findById(m.getId()).orElseThrow().getMedia().mediaId()).isNull();
        }
    }

    @Test
    void deviceThatIsNotReadyIs409() throws Exception {
        WaWebChat chat = chatWithAarav();
        WaWebMessage m = imageMessage(chat);
        try (FakeDevice device = FakeDevice.connectAndAuth(port, testDevices.redeem(number).deviceKey())) {
            Thread.sleep(200);

            ResponseEntity<JsonNode> res = json(managerToken, chat, m);

            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(res.getBody().get("code").asString()).isEqualTo("WHATSAPP_NOT_READY");
            assertThat(device.poll(Duration.ofMillis(300))).isEmpty();
        }
    }

    /** The other half, a 3 MB frame from an authenticated device, is {@link #aLargeFileCrossesTheSocket()}. */
    @Test
    void anUnauthenticatedSocketKeepsTheSmallFrameLimit() throws Exception {
        try (FakeDevice device = FakeDevice.connect(port)) {
            device.send(Envelope.event("noise", mapper.createObjectNode().put("pad", "x".repeat(16 * 1024))));

            assertThat(device.awaitClose().getCode()).isEqualTo(1009);
        }
    }

    @Test
    void unavailableMediaIs410() throws Exception {
        WaWebChat chat = chatWithAarav();
        WaWebMessage m = imageMessage(chat);

        var ex = viaDevice(() -> json(managerToken, chat, m),
                req -> Envelope.errorResponse(req.id(), "MEDIA_UNAVAILABLE", "gone"));

        assertThat(ex.getValue().getStatusCode()).isEqualTo(HttpStatus.GONE);
        assertThat(ex.getValue().getBody().get("code").asString()).isEqualTo("MEDIA_UNAVAILABLE");
        assertThat(ex.getValue().getBody().get("message").asString())
                .isEqualTo("Media is no longer available on the phone");
        assertThat(messages.findById(m.getId()).orElseThrow().getMedia().mediaId()).isNull();
    }

    @Test
    void oversizedReplyIs410() throws Exception {
        WaWebChat chat = chatWithAarav();
        WaWebMessage m = imageMessage(chat);

        var ex = viaDevice(() -> json(managerToken, chat, m),
                req -> Envelope.response(req.id(), reply(new byte[16 * 1024 * 1024 + 1], "image/png", "a.png")));

        assertThat(ex.getValue().getStatusCode()).isEqualTo(HttpStatus.GONE);
        assertThat(messages.findById(m.getId()).orElseThrow().getMedia().mediaId()).isNull();
    }

    @Test
    void mediaKnownToBeOver16MbIs410WithoutAskingThePhone() throws Exception {
        WaWebChat chat = chatWithAarav();
        WaWebMessage m = message(chat, 10, "huge");
        m.setType("video");
        m.setMedia(new WaWebMessage.Media("video/mp4", "big.mp4", 20L * 1024 * 1024, null, null));
        WaWebMessage big = messages.save(m);
        try (FakeDevice device = FakeDevice.connectAndAuth(port, testDevices.redeem(number).deviceKey())) {
            device.send(Envelope.event("ready", ready()));
            Thread.sleep(200);

            ResponseEntity<JsonNode> res = json(managerToken, chat, big);

            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.GONE);
            assertThat(res.getBody().get("code").asString()).isEqualTo("MEDIA_UNAVAILABLE");
            assertThat(res.getBody().get("message").asString()).isEqualTo("Media is too large to fetch from the phone");
            assertThat(device.poll(Duration.ofMillis(300))).isEmpty();
        }
    }

    @Test
    void noDeviceIs409() {
        WaWebChat chat = chatWithAarav();
        WaWebMessage m = imageMessage(chat);

        ResponseEntity<JsonNode> res = json(managerToken, chat, m);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(res.getBody().get("code").asString()).isEqualTo("NO_DEVICE");
    }

    @Test
    void messageWithoutMediaIs404() throws Exception {
        WaWebChat chat = chatWithAarav();
        WaWebMessage m = message(chat, 10, "just text");
        try (FakeDevice device = FakeDevice.connectAndAuth(port, testDevices.redeem(number).deviceKey())) {
            device.send(Envelope.event("ready", ready()));
            Thread.sleep(200);

            assertThat(json(managerToken, chat, m).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(device.poll(Duration.ofMillis(300))).isEmpty();
        }
    }

    @Test
    void outsideScopeOrOtherChatIs404() {
        WaWebChat mine = chat("Mine", "919800000001", 1, agent2, null);
        WaWebMessage m = imageMessage(mine);
        assertThat(json(agentToken, mine, m).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        WaWebChat visible = chatWithAarav();
        assertThat(json(managerToken, visible, m).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void templateImageSendCachesMedia() throws Exception {
        WaWebChat chat = chatWithAarav();
        var me = access.load(manager);
        MediaView media = templateMedia.upload(me, brand.getId(), "logo.png", png());
        TemplateContent c = new TemplateContent();
        c.setCaption("Hi");
        String templateId = templateService.create(me, brand.getId(),
                new TemplateRequest("Pic", null, TemplateType.IMAGE, c, media.mediaId(), List.of(), null)).id();
        String serialized = "true_" + chat.getRemoteId() + "_1";
        ObjectNode model = mapper.createObjectNode();
        model.set("id", mapper.createObjectNode().put("_serialized", serialized).put("fromMe", true)
                .put("remote", chat.getRemoteId()));
        model.put("fromMe", true).put("body", "Hi").put("type", "image").put("ack", 1)
                .put("timestamp", Instant.now().getEpochSecond());

        try (FakeDevice device = FakeDevice.connectAndAuth(port, testDevices.redeem(number).deviceKey())) {
            device.send(Envelope.event("ready", ready()));
            Thread.sleep(200);
            HttpHeaders h = new HttpHeaders();
            h.setBearerAuth(managerToken);
            h.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
            CompletableFuture<ResponseEntity<JsonNode>> http = CompletableFuture.supplyAsync(() -> rest.exchange(
                    inbox() + "/chats/" + chat.getId() + "/messages", HttpMethod.POST,
                    new HttpEntity<>("{\"templateId\":\"" + templateId + "\"}", h), JsonNode.class));
            Envelope req = device.next();
            assertThat(req.method()).isEqualTo("sendMessage");
            device.send(Envelope.response(req.id(), model));
            ResponseEntity<JsonNode> sent = http.get(5, TimeUnit.SECONDS);
            assertThat(sent.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(sent.getBody().get("media").get("available").asBoolean()).isTrue();

            WaWebMessage stored = messages.findByPhoneNumberIdAndWaMessageId(number.getId(), serialized).orElseThrow();
            assertThat(stored.getMedia().mediaId()).isNotNull();
            assertThat(stored.getMedia().downloadedAt()).isNotNull();

            ResponseEntity<byte[]> res = bytes(managerToken, chat, stored);
            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(res.getBody()).isEqualTo(png());
            assertThat(device.poll(Duration.ofMillis(300))).isEmpty();
        }
    }

    @Test
    void deletingNumberRemovesInboxData() throws Exception {
        WaWebChat chat = chatWithAarav();
        WaWebMessage m = imageMessage(chat);
        action(chat, 1, ActionType.ASSIGNED);
        String mediaId = chatMedia.store(brand.getId(), number.getId(), m.getId(), "image/png", "a.png", png());
        // another number's data stays
        WaWebChat otherChat = chat(otherNumber, other, contact(other, "Zed", "919800000009"), 1);
        WaWebMessage otherMessage = message(otherChat, 1, "keep");
        String otherMedia = chatMedia.store(other.getId(), otherNumber.getId(), otherMessage.getId(), "image/png",
                "b.png", png());

        phoneNumbers.delete(number);

        assertThat(chats.findAll()).extracting(WaWebChat::getId).containsExactly(otherChat.getId());
        assertThat(messages.findAll()).extracting(WaWebMessage::getId).containsExactly(otherMessage.getId());
        assertThat(actions.count()).isZero();
        assertThat(chatMediaGridFs.findOne(new Query(
                org.springframework.data.mongodb.core.query.Criteria.where("_id").is(new ObjectId(mediaId)))))
                .isNull();
        assertThat(chatMediaGridFs.findOne(new Query(
                org.springframework.data.mongodb.core.query.Criteria.where("_id").is(new ObjectId(otherMedia)))))
                .isNotNull();
    }
}
