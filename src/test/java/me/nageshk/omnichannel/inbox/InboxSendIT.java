package me.nageshk.omnichannel.inbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import me.nageshk.omnichannel.access.AccessService;
import me.nageshk.omnichannel.auth.AuthenticatedUser;
import me.nageshk.omnichannel.contact.Contact;
import me.nageshk.omnichannel.events.WhatsAppEvent;
import me.nageshk.omnichannel.rpc.Envelope;
import me.nageshk.omnichannel.support.FakeDevice;
import me.nageshk.omnichannel.template.MediaView;
import me.nageshk.omnichannel.template.MessageTemplate;
import me.nageshk.omnichannel.template.MessageTemplateRepository;
import me.nageshk.omnichannel.template.TemplateContent;
import me.nageshk.omnichannel.template.TemplateMediaService;
import me.nageshk.omnichannel.template.TemplateRequest;
import me.nageshk.omnichannel.template.TemplateRequest.PlaceholderSetting;
import me.nageshk.omnichannel.template.TemplateService;
import me.nageshk.omnichannel.template.TemplateType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Sending from the console: text and templates through a {@link FakeDevice} standing in for the extension. */
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "omnichannel.rpc.timeout=2s")
class InboxSendIT extends InboxApiTestBase {

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired ApplicationEventPublisher publisher;
    @Autowired AccessService access;
    @Autowired TemplateService templateService;
    @Autowired TemplateMediaService templateMedia;
    @Autowired MessageTemplateRepository templates;
    @Autowired InboxSendService sender;

    /** What the device saw and what the console got back. */
    record Exchange(ResponseEntity<JsonNode> response, Envelope request) {
    }

    private String url(WaWebChat chat) {
        return inbox() + "/chats/" + chat.getId() + "/messages";
    }

    private ResponseEntity<JsonNode> post(String token, WaWebChat chat, Object body) throws Exception {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(url(chat), HttpMethod.POST, new HttpEntity<>(mapper.writeValueAsString(body), h),
                JsonNode.class);
    }

    private ObjectNode ready() {
        return mapper.createObjectNode().put("pushname", "N").put("wid", number.getNumber() + "@c.us");
    }

    /** A sent-message model the way whatsapp-web.js reports it. */
    private ObjectNode model(WaWebChat chat, String serialized, String body, String type, int ack) {
        ObjectNode m = mapper.createObjectNode();
        m.set("id", mapper.createObjectNode().put("_serialized", serialized).put("fromMe", true)
                .put("remote", chat.getRemoteId()));
        m.put("fromMe", true).put("body", body).put("type", type).put("ack", ack)
                .put("timestamp", Instant.now().getEpochSecond());
        return m;
    }

    /** Posts with a connected device and answers the {@code sendMessage} request it receives. */
    private Exchange viaDevice(String token, WaWebChat chat, Object body, Function<Envelope, Envelope> answer)
            throws Exception {
        try (FakeDevice device = FakeDevice.connectAndAuth(port, testDevices.redeem(number).deviceKey())) {
            device.send(Envelope.event("ready", ready()));
            Thread.sleep(200);
            CompletableFuture<ResponseEntity<JsonNode>> http = CompletableFuture.supplyAsync(() -> {
                try {
                    return post(token, chat, body);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            Envelope req = device.next();
            assertThat(req.method()).isEqualTo("sendMessage");
            device.send(answer.apply(req));
            return new Exchange(http.get(5, TimeUnit.SECONDS), req);
        }
    }

    private Exchange sendOk(String token, WaWebChat chat, Object body, JsonNode result) throws Exception {
        return viaDevice(token, chat, body, req -> Envelope.response(req.id(), result));
    }

    private Exchange sendOk(String token, WaWebChat chat, Object body, String serialized, String text, String type)
            throws Exception {
        return sendOk(token, chat, body, model(chat, serialized, text, type, 1));
    }

    private AuthenticatedUser managerMe() {
        return access.load(manager);
    }

    private PlaceholderSetting agentSetting(String name) {
        return new PlaceholderSetting(name, null, name, null, true);
    }

    private PlaceholderSetting nameSetting() {
        return new PlaceholderSetting("contact.name", "there", null, null, null);
    }

    private String template(String name, TemplateType type, TemplateContent content, String mediaId,
            PlaceholderSetting... settings) {
        return templateService.create(managerMe(), brand.getId(),
                new TemplateRequest(name, null, type, content, mediaId, List.of(settings), null)).id();
    }

    private String textTemplate(String name, String body, PlaceholderSetting... settings) {
        TemplateContent c = new TemplateContent();
        c.setBody(body);
        return template(name, TemplateType.TEXT, c, null, settings);
    }

    private static byte[] png() {
        byte[] png = new byte[40];
        byte[] sig = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
        System.arraycopy(sig, 0, png, 0, sig.length);
        return png;
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

    @Test
    void textSendGoesThroughDeviceAndIsStoredAsConsole() throws Exception {
        WaWebChat chat = chatWithAarav();
        Exchange ex = sendOk(managerToken, chat, Map.of("text", "  hello  "), "true_" + chat.getRemoteId() + "_1",
                "hello", "chat");

        assertThat(ex.response().getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ex.request().params().get("chatId").asString()).isEqualTo(chat.getRemoteId());
        assertThat(ex.request().params().get("content").asString()).isEqualTo("hello");
        assertThat(ex.request().params().get("sendSeen").asBoolean()).isTrue();
        JsonNode view = ex.response().getBody();
        assertThat(view.get("direction").asString()).isEqualTo("OUT");
        assertThat(view.get("source").asString()).isEqualTo("CONSOLE");
        assertThat(view.get("status").asString()).isEqualTo("SENT");
        assertThat(view.get("sentBy").get("name").asString()).isEqualTo(manager.getName());

        WaWebMessage stored = only(chat);
        assertThat(stored.getWaMessageId()).isEqualTo("true_" + chat.getRemoteId() + "_1");
        assertThat(stored.getDirection()).isEqualTo(MessageDirection.OUT);
        assertThat(stored.getSource()).isEqualTo(MessageSource.CONSOLE);
        assertThat(stored.getStatus()).isEqualTo(MessageStatus.SENT);
        assertThat(stored.getType()).isEqualTo("chat");
        assertThat(stored.getBody()).isEqualTo("hello");
        assertThat(stored.getSentByUserId()).isEqualTo(manager.getId());
        assertThat(stored.getSentByName()).isEqualTo(manager.getName());
        WaWebChat after = chats.findById(chat.getId()).orElseThrow();
        assertThat(after.getLastMessage().text()).isEqualTo("hello");
        assertThat(after.getLastMessage().direction()).isEqualTo(MessageDirection.OUT);
        assertThat(after.getLastOutboundAt()).isNotNull();
    }

    @Test
    void laterMessageCreateKeepsConsoleSource() throws Exception {
        WaWebChat chat = chatWithAarav();
        String serialized = "true_" + chat.getRemoteId() + "_2";
        sendOk(managerToken, chat, Map.of("text", "hello"), serialized, "hello", "chat");

        ObjectNode later = model(chat, serialized, "hello", "chat", 2);
        publisher.publishEvent(new WhatsAppEvent(this, number.getId(), brand.getId(), acme.getId(), "message_create",
                later, null, System.currentTimeMillis()));

        WaWebMessage stored = only(chat);
        assertThat(stored.getSource()).isEqualTo(MessageSource.CONSOLE);
        assertThat(stored.getSentByUserId()).isEqualTo(manager.getId());
        assertThat(stored.getStatus()).isEqualTo(MessageStatus.DELIVERED);
    }

    @Test
    void messageCreateBeforeTheRpcResultIsTakenOverByTheConsole() throws Exception {
        WaWebChat chat = chatWithAarav();
        String serialized = "true_" + chat.getRemoteId() + "_3";
        Exchange ex = viaDevice(managerToken, chat, Map.of("text", "hello"), req -> {
            publisher.publishEvent(new WhatsAppEvent(this, number.getId(), brand.getId(), acme.getId(),
                    "message_create", model(chat, serialized, "hello", "chat", 2), null, System.currentTimeMillis()));
            return Envelope.response(req.id(), model(chat, serialized, "hello", "chat", 1));
        });

        assertThat(ex.response().getStatusCode()).isEqualTo(HttpStatus.OK);
        WaWebMessage stored = only(chat);
        assertThat(stored.getSource()).isEqualTo(MessageSource.CONSOLE);
        assertThat(stored.getSentByUserId()).isEqualTo(manager.getId());
        assertThat(stored.getStatus()).isEqualTo(MessageStatus.DELIVERED);
    }

    @Test
    void nullResultStoresAPendingPlaceholder() throws Exception {
        WaWebChat chat = chatWithAarav();
        Exchange ex = sendOk(managerToken, chat, Map.of("text", "console says hi"), mapper.nullNode());

        assertThat(ex.response().getStatusCode()).isEqualTo(HttpStatus.OK);
        WaWebMessage stored = only(chat);
        assertThat(stored.getWaMessageId()).startsWith("console:");
        assertThat(stored.getStatus()).isEqualTo(MessageStatus.PENDING);
        assertThat(stored.getSource()).isEqualTo(MessageSource.CONSOLE);
        assertThat(stored.getBody()).isEqualTo("console says hi");
        WaWebChat.LastMessage preview = chats.findById(chat.getId()).orElseThrow().getLastMessage();
        assertThat(preview.direction()).isEqualTo(MessageDirection.OUT);
        assertThat(preview.text()).isEqualTo("console says hi");
        assertThat(preview.status()).isEqualTo(MessageStatus.PENDING);
    }

    @Test
    void deviceFailureStoresNothing() throws Exception {
        WaWebChat chat = chatWithAarav();
        Exchange ex = viaDevice(managerToken, chat, Map.of("text", "console says hi"),
                req -> Envelope.errorResponse(req.id(), "CHAT_NOT_FOUND", "Chat not found"));

        assertThat(ex.response().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ex.response().getBody().get("code").asString()).isEqualTo("CHAT_NOT_FOUND");
        assertThat(messages.findAll().stream().filter(m -> chat.getId().equals(m.getChatId()))).isEmpty();
        WaWebChat.LastMessage preview = chats.findById(chat.getId()).orElseThrow().getLastMessage();
        assertThat(preview.direction()).isEqualTo(MessageDirection.IN);
        assertThat(preview.text()).isEqualTo("hello");
        assertThat(chats.findById(chat.getId()).orElseThrow().getLastOutboundAt()).isNull();
    }

    @Test
    void closedChatIs409() throws Exception {
        WaWebChat chat = chatWithAarav();
        chat.setStatus(ChatStatus.CLOSED);
        chats.save(chat);

        ResponseEntity<JsonNode> res = post(managerToken, chat, Map.of("text", "hello"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(res.getBody().get("code").asString()).isEqualTo("CHAT_CLOSED");
        assertThat(res.getBody().get("message").asString()).isEqualTo("This chat is closed");
    }

    @Test
    void noDeviceIs409() throws Exception {
        WaWebChat chat = chatWithAarav();

        ResponseEntity<JsonNode> res = post(managerToken, chat, Map.of("text", "hello"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(res.getBody().get("code").asString()).isEqualTo("NO_DEVICE");
        assertThat(messages.count()).isZero();
    }

    @Test
    void invisibleChatIs404() throws Exception {
        WaWebChat chat = chat("Mine", "919800000001", 1, agent2, null);

        ResponseEntity<JsonNode> res = post(agentToken, chat, Map.of("text", "hello"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void textMustBeExactlyOneOfTextOrTemplate() throws Exception {
        WaWebChat chat = chatWithAarav();
        try (FakeDevice device = FakeDevice.connectAndAuth(port, testDevices.redeem(number).deviceKey())) {
            device.send(Envelope.event("ready", ready()));
            Thread.sleep(200);
            for (Object body : List.<Object>of(Map.of(), Map.of("text", "   "), Map.of("text", "x".repeat(4097)),
                    Map.of("text", "hi", "templateId", "t1"))) {
                ResponseEntity<JsonNode> res = post(managerToken, chat, body);
                assertThat(res.getStatusCode()).as(body.toString()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(res.getBody().get("code").asString()).isEqualTo("VALIDATION");
                assertThat(res.getBody().get("fields").has("text")).as(body.toString()).isTrue();
            }
            assertThat(post(managerToken, chat, Map.of("text", "x".repeat(4097))).getBody().get("fields").get("text")
                    .asString()).isNotEqualTo("required");
            assertThat(post(managerToken, chat, Map.of()).getBody().get("fields").get("text").asString())
                    .isEqualTo("required");
            assertThat(device.poll(Duration.ofMillis(300))).isEmpty();
        }
    }

    @Test
    void claimOnReplyAssignsSender() throws Exception {
        WaWebChat chat = chatWithAarav();
        assertThat(chat.getAssignedUserId()).isNull();

        sendOk(managerToken, chat, Map.of("text", "hello"), "true_" + chat.getRemoteId() + "_4", "hello", "chat");

        WaWebChat after = chats.findById(chat.getId()).orElseThrow();
        assertThat(after.getAssignedUserId()).isEqualTo(manager.getId());
        assertThat(actions.findAll().stream().filter(a -> chat.getId().equals(a.getChatId()))
                .map(WaWebChatAction::getType)).contains(ActionType.ASSIGNED);
    }

    @Test
    void templateTextRendersContactName() throws Exception {
        WaWebChat chat = chatWithAarav();
        String id = textTemplate("Hello", "Hi {{contact.name}}", nameSetting());

        Exchange ex = sendOk(managerToken, chat, Map.of("templateId", id), "true_" + chat.getRemoteId() + "_5",
                "Hi Aarav", "chat");

        assertThat(ex.response().getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ex.request().params().get("content").asString()).isEqualTo("Hi Aarav");
        WaWebMessage stored = only(chat);
        assertThat(stored.getTemplateId()).isEqualTo(id);
        assertThat(stored.getBody()).isEqualTo("Hi Aarav");
        assertThat(stored.getType()).isEqualTo("chat");
    }

    @Test
    void templateTextPassesLinkPreview() throws Exception {
        WaWebChat chat = chatWithAarav();
        TemplateContent c = new TemplateContent();
        c.setBody("See https://example.com");
        c.setLinkPreview(false);
        String id = template("Link", TemplateType.TEXT, c, null);

        Exchange ex = sendOk(managerToken, chat, Map.of("templateId", id), "true_" + chat.getRemoteId() + "_6",
                "See https://example.com", "chat");

        assertThat(ex.request().params().get("options").get("linkPreview").asBoolean()).isFalse();
    }

    @Test
    void templateMissingValueNeverReachesDevice() throws Exception {
        WaWebChat chat = chatWithAarav();
        String id = textTemplate("Order", "Order {{order_id}} shipped", agentSetting("order_id"));

        try (FakeDevice device = FakeDevice.connectAndAuth(port, testDevices.redeem(number).deviceKey())) {
            device.send(Envelope.event("ready", ready()));
            Thread.sleep(200);

            ResponseEntity<JsonNode> res = post(managerToken, chat, Map.of("templateId", id, "values", Map.of()));

            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(res.getBody().get("code").asString()).isEqualTo("VALIDATION");
            assertThat(res.getBody().get("fields").get("order_id").asString()).isEqualTo("required");
            assertThat(device.poll(Duration.ofSeconds(1))).isEmpty();
        }
        assertThat(messages.count()).isZero();
    }

    @Test
    void templateWithValueFillsIt() throws Exception {
        WaWebChat chat = chatWithAarav();
        String id = textTemplate("Order", "Order {{order_id}} shipped", agentSetting("order_id"));

        Exchange ex = sendOk(managerToken, chat, Map.of("templateId", id, "values", Map.of("order_id", "A-17")),
                "true_" + chat.getRemoteId() + "_7", "Order A-17 shipped", "chat");

        assertThat(ex.request().params().get("content").asString()).isEqualTo("Order A-17 shipped");
    }

    @Test
    void templateImageSendsMediaOptions() throws Exception {
        WaWebChat chat = chatWithAarav();
        MediaView media = templateMedia.upload(managerMe(), brand.getId(), "logo.png", png());
        TemplateContent c = new TemplateContent();
        c.setCaption("Hi {{contact.name}}");
        String id = template("Pic", TemplateType.IMAGE, c, media.mediaId(), nameSetting());

        Exchange ex = sendOk(managerToken, chat, Map.of("templateId", id), "true_" + chat.getRemoteId() + "_8",
                "Hi Aarav", "image");

        assertThat(ex.response().getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode params = ex.request().params();
        assertThat(params.get("content").asString()).isEqualTo("Hi Aarav");
        JsonNode sent = params.get("options").get("media");
        assertThat(sent.get("mimetype").asString()).isEqualTo("image/png");
        assertThat(sent.get("filename").asString()).isEqualTo("logo.png");
        assertThat(sent.get("data").asString()).isEqualTo(Base64.getEncoder().encodeToString(png()));
        assertThat(params.get("options").get("caption").asString()).isEqualTo("Hi Aarav");
        WaWebMessage stored = only(chat);
        assertThat(stored.getType()).isEqualTo("image");
        assertThat(stored.getBody()).isEqualTo("Hi Aarav");
        assertThat(stored.getMedia().mimetype()).isEqualTo("image/png");
        assertThat(stored.getMedia().filename()).isEqualTo("logo.png");
        assertThat(stored.getMedia().size()).isEqualTo(png().length);
        assertThat(stored.getMedia().mediaId()).isNotNull();
        assertThat(stored.getMedia().downloadedAt()).isNotNull();
    }

    @Test
    void templateDocumentSendsItAsDocument() throws Exception {
        WaWebChat chat = chatWithAarav();
        byte[] pdf = "%PDF-1.4\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);
        MediaView media = templateMedia.upload(managerMe(), brand.getId(), "terms.pdf", pdf);
        String id = template("Terms", TemplateType.DOCUMENT, new TemplateContent(), media.mediaId());

        Exchange ex = sendOk(managerToken, chat, Map.of("templateId", id), "true_" + chat.getRemoteId() + "_9",
                "", "document");

        assertThat(ex.response().getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode options = ex.request().params().get("options");
        assertThat(options.get("sendMediaAsDocument").asBoolean()).isTrue();
        assertThat(options.get("media").get("mimetype").asString()).isEqualTo("application/pdf");
        assertThat(ex.request().params().get("content").asString()).isEmpty();
        assertThat(only(chat).getType()).isEqualTo("document");
    }

    @Test
    void templateLocationSendsLocationOptions() throws Exception {
        WaWebChat chat = chatWithAarav();
        TemplateContent c = new TemplateContent();
        c.setLatitude(12.5);
        c.setLongitude(77.25);
        c.setName("Head office");
        c.setAddress("1 MG Road");
        String id = template("Where", TemplateType.LOCATION, c, null);

        Exchange ex = sendOk(managerToken, chat, Map.of("templateId", id), "true_" + chat.getRemoteId() + "_10",
                "", "location");

        assertThat(ex.response().getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode location = ex.request().params().get("options").get("location");
        assertThat(location.get("latitude").asDouble()).isEqualTo(12.5);
        assertThat(location.get("longitude").asDouble()).isEqualTo(77.25);
        assertThat(location.get("description").asString()).isEqualTo("Head office, 1 MG Road");
        assertThat(ex.request().params().get("content").asString()).isEmpty();
        WaWebMessage stored = only(chat);
        assertThat(stored.getType()).isEqualTo("location");
        assertThat(stored.getLocation().latitude()).isEqualTo(12.5);
        assertThat(stored.getLocation().description()).isEqualTo("Head office, 1 MG Road");
    }

    @Test
    void templatePollSendsPollOptions() throws Exception {
        WaWebChat chat = chatWithAarav();
        TemplateContent c = new TemplateContent();
        c.setQuestion("Lunch?");
        c.setOptions(List.of("Pizza", "Sushi"));
        c.setAllowMultiple(true);
        String id = template("Poll", TemplateType.POLL, c, null);

        Exchange ex = sendOk(managerToken, chat, Map.of("templateId", id), "true_" + chat.getRemoteId() + "_11",
                "Lunch?", "poll_creation");

        assertThat(ex.response().getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode poll = ex.request().params().get("options").get("poll");
        assertThat(poll.get("pollName").asString()).isEqualTo("Lunch?");
        assertThat(poll.get("pollOptions").get(0).asString()).isEqualTo("Pizza");
        assertThat(poll.get("pollOptions").get(1).asString()).isEqualTo("Sushi");
        assertThat(poll.get("options").get("allowMultipleAnswers").asBoolean()).isTrue();
        assertThat(only(chat).getType()).isEqualTo("poll_creation");
    }

    @Test
    void contactCardTemplateIsRejected() throws Exception {
        WaWebChat chat = chatWithAarav();
        TemplateContent c = new TemplateContent();
        c.setFullName("Asha Rao");
        c.setPhone("+919876543210");
        String id = template("Card", TemplateType.CONTACT_CARD, c, null);

        try (FakeDevice device = FakeDevice.connectAndAuth(port, testDevices.redeem(number).deviceKey())) {
            device.send(Envelope.event("ready", ready()));
            Thread.sleep(200);

            ResponseEntity<JsonNode> res = post(managerToken, chat, Map.of("templateId", id));

            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(res.getBody().get("fields").get("templateId").asString())
                    .isEqualTo("contact cards cannot be sent from the inbox yet");
            assertThat(device.poll(Duration.ofMillis(500))).isEmpty();
        }
    }

    @Test
    void templateMarksLastUsed() throws Exception {
        WaWebChat chat = chatWithAarav();
        String id = textTemplate("Hello", "Hi {{contact.name}}", nameSetting());
        MessageTemplate before = templates.findById(id).orElseThrow();
        assertThat(before.getLastUsedAt()).isNull();

        sendOk(managerToken, chat, Map.of("templateId", id), "true_" + chat.getRemoteId() + "_12", "Hi Aarav", "chat");

        MessageTemplate after = templates.findById(id).orElseThrow();
        assertThat(after.getLastUsedAt()).isNotNull();
        assertThat(after.getVersion()).isEqualTo(before.getVersion());
    }

    @Test
    void archivedTemplateIsRejectedBeforeTheDevice() throws Exception {
        WaWebChat chat = chatWithAarav();
        String id = textTemplate("Old", "Hi {{contact.name}}", nameSetting());
        templateService.archive(managerMe(), brand.getId(), id);

        try (FakeDevice device = FakeDevice.connectAndAuth(port, testDevices.redeem(number).deviceKey())) {
            device.send(Envelope.event("ready", ready()));
            Thread.sleep(200);

            ResponseEntity<JsonNode> res = post(managerToken, chat, Map.of("templateId", id));

            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(res.getBody().get("code").asString()).isEqualTo("VALIDATION");
            assertThat(res.getBody().get("fields").get("templateId").asString()).isEqualTo("archived");
            assertThat(device.poll(Duration.ofSeconds(1))).isEmpty();
        }
        assertThat(messages.count()).isZero();
        assertThat(templates.findById(id).orElseThrow().getLastUsedAt()).isNull();
    }

    @Test
    void unknownTemplateIs404() throws Exception {
        WaWebChat chat = chatWithAarav();
        try (FakeDevice device = FakeDevice.connectAndAuth(port, testDevices.redeem(number).deviceKey())) {
            device.send(Envelope.event("ready", ready()));
            Thread.sleep(200);
            assertThat(post(managerToken, chat, Map.of("templateId", "nope")).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    @Test
    void unsavedChatIsStoredWithTheFirstMessage() throws Exception {
        Contact contact = contact(brand, "Nia", "919811111111");
        WaWebChat fresh = new WaWebChat();
        fresh.setCustomerId(acme.getId());
        fresh.setWorkspaceId(brand.getId());
        fresh.setPhoneNumberId(number.getId());
        fresh.setRemoteId("919811111111@c.us");
        fresh.setContactId(contact.getId());
        fresh.setStatus(ChatStatus.OPEN);
        fresh.setAssignedUserId(agent.getId());

        try (FakeDevice device = FakeDevice.connectAndAuth(port, testDevices.redeem(number).deviceKey())) {
            device.send(Envelope.event("ready", ready()));
            Thread.sleep(200);
            CompletableFuture<MessageView> sent = sender.send(access.load(agent), brand.getId(), fresh,
                    new SendRequest("hi Nia", null, null));
            Envelope req = device.next();
            device.send(Envelope.response(req.id(), model(fresh, "true_919811111111@c.us_1", "hi Nia", "chat", 1)));
            MessageView view = sent.get(5, TimeUnit.SECONDS);

            WaWebChat stored = chats.findByPhoneNumberIdAndRemoteId(number.getId(), "919811111111@c.us").orElseThrow();
            assertThat(view.chatId()).isEqualTo(stored.getId());
            assertThat(stored.getAssignedUserId()).isEqualTo(agent.getId());
            assertThat(stored.getLastMessage().text()).isEqualTo("hi Nia");
            assertThat(messages.findByPhoneNumberIdAndWaMessageId(number.getId(), "true_919811111111@c.us_1")
                    .orElseThrow().getChatId()).isEqualTo(stored.getId());
        }
    }

    @Test
    void unsavedChatFallsBackToTheRowAnInboundMessageCreatedMeanwhile() throws Exception {
        // the same remote id gets a chat before the send's record step runs: the existing row wins
        WaWebChat existing = chat("Nia", "919811111111", 1);
        existing.setRemoteId("919811111111@c.us");
        existing = chats.save(existing);
        WaWebChat fresh = new WaWebChat();
        fresh.setCustomerId(acme.getId());
        fresh.setWorkspaceId(brand.getId());
        fresh.setPhoneNumberId(number.getId());
        fresh.setRemoteId("919811111111@c.us");
        fresh.setContactId(existing.getContactId());
        fresh.setStatus(ChatStatus.OPEN);
        fresh.setAssignedUserId(manager.getId());

        try (FakeDevice device = FakeDevice.connectAndAuth(port, testDevices.redeem(number).deviceKey())) {
            device.send(Envelope.event("ready", ready()));
            Thread.sleep(200);
            CompletableFuture<MessageView> sent = sender.send(access.load(agent), brand.getId(), fresh,
                    new SendRequest("hi again", null, null));
            Envelope req = device.next();
            device.send(Envelope.response(req.id(), model(fresh, "true_919811111111@c.us_2", "hi again", "chat", 1)));
            MessageView view = sent.get(5, TimeUnit.SECONDS);

            assertThat(view.chatId()).isEqualTo(existing.getId());
            assertThat(chats.count()).isEqualTo(1);
            // the existing row keeps its own state: it is claimed by the replying agent (claim on reply) and does
            // not take the manager the unsaved chat was assigned to
            assertThat(chats.findById(existing.getId()).orElseThrow().getAssignedUserId()).isEqualTo(agent.getId());
        }
    }
}
