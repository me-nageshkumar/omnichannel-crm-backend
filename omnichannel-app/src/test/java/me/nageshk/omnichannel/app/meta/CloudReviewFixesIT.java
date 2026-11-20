package me.nageshk.omnichannel.app.meta;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import me.nageshk.omnichannel.app.support.GraphStub;
import me.nageshk.omnichannel.app.support.TestBilling;
import me.nageshk.omnichannel.common.billing.BillingPeriod;
import me.nageshk.omnichannel.common.billing.UsageMeter;
import me.nageshk.omnichannel.db.billing.PlanTerms;
import me.nageshk.omnichannel.db.billing.Subscription;
import me.nageshk.omnichannel.db.billing.SubscriptionRepository;
import me.nageshk.omnichannel.db.billing.WalletRepository;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import me.nageshk.omnichannel.db.inbox.WaWebMessage;
import me.nageshk.omnichannel.security.entitlement.Entitlements;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/** The backend final review's Important findings, each pinned (2026-11-20). */
class CloudReviewFixesIT extends MetaTestBase {

    @Autowired TestBilling billing;
    @Autowired SubscriptionRepository subscriptions;
    @Autowired WalletRepository wallets;
    @Autowired Entitlements entitlements;

    @AfterEach
    void noBilling() {
        billing.reset();
    }

    private void plan() {
        Subscription s = billing.assign(acme.getId(), Set.of(), Map.of());
        s.getCurrent().setTerms(new PlanTerms("Gold", BillingPeriod.MONTHLY, 2, null, List.of(), List.of(), List.of(),
                Map.of(), Map.of(UsageMeter.WHATSAPP_MESSAGE, new BigDecimal("0.25"))));
        subscriptions.save(s);
        entitlements.evict(acme.getId());
        billing.wallet(acme.getId(), "100.0000");
    }

    private BigDecimal balance() {
        return wallets.findByCustomerId(acme.getId()).orElseThrow().getBalance();
    }

    /** A connection, a number, and an open chat with Veenu. */
    private WaWebChat cloudChat() throws Exception {
        mongo.remove(new Query(), "channelWebhookEvents");
        JsonNode connection = connect("111");
        String numberId = addCloudNumber(connection.get("id").asString(), "Admissions", "333", "+91 98765 43210")
                .get("id").asString();
        webhook(keyOf(connection), envelope(textChange("333", "919876543210", "919799750743", "wamid.IN1", "Hi")));
        await(() -> !mongo.find(new Query(Criteria.where("phoneNumberId").is(numberId)), WaWebMessage.class).isEmpty());
        GRAPH.reset();
        return chats.findAll().stream().filter(c -> numberId.equals(c.getPhoneNumberId())).findFirst().orElseThrow();
    }

    private ResponseEntity<JsonNode> sendText(WaWebChat chat, String text) {
        return rest.exchange(url("/api/workspaces/" + brand.getId() + "/inbox/chats/" + chat.getId() + "/messages"),
                HttpMethod.POST, auth(manager, Map.of("text", text)), JsonNode.class);
    }

    @Test
    void metaVerifiesTheCallbackDuringSetup() {
        AtomicInteger verify = new AtomicInteger();
        GRAPH.before("POST /111/subscriptions", () -> {
            // Meta calls our callback before it answers
            Document c = mongo.getCollection("metaConnections").find(new Document("appId", "111")).first();
            if (c == null) {
                verify.set(404);
                return;
            }
            verify.set(rest.getForEntity(url("/hooks/meta/" + c.getString("webhookKey") + "?hub.mode=subscribe"
                    + "&hub.verify_token=" + c.getString("verifyToken") + "&hub.challenge=abc"), String.class)
                    .getStatusCode().value());
        });

        JsonNode view = connect("111");

        assertThat(verify.get()).isEqualTo(200);
        assertThat(view.get("webhook").get("setup").asString()).isEqualTo("AUTO");
    }

    @Test
    void failedStatusBeforeTheChargeIsStampedIsGivenBack() throws Exception {
        WaWebChat chat = cloudChat();
        plan();
        String key = keyOf(meta(HttpMethod.GET, "/connections", manager, null).getBody().get(0));
        GRAPH.before("POST /333/messages", () -> webhook(key, envelope("{\"field\":\"messages\",\"value\":{"
                + "\"messaging_product\":\"whatsapp\",\"metadata\":{\"display_phone_number\":\"919876543210\","
                + "\"phone_number_id\":\"333\"},\"statuses\":[{\"id\":\"wamid.F0\",\"status\":\"failed\",\"timestamp\":\""
                + Instant.now().getEpochSecond() + "\",\"recipient_id\":\"919799750743\",\"errors\":[{\"code\":131026,"
                + "\"title\":\"Message undeliverable\"}]}]}}")));
        GRAPH.enqueue("POST /333/messages", 200, "{\"messages\":[{\"id\":\"wamid.F0\"}]}");

        assertThat(sendText(chat, "Hi").getStatusCode()).isEqualTo(HttpStatus.OK);

        await(() -> balance().compareTo(new BigDecimal("100.0000")) == 0);
    }

    @Test
    void refusedMediaUploadIsNotChargedAndShowsMetasReason() throws Exception {
        WaWebChat chat = cloudChat();
        plan();
        byte[] png = new byte[100];
        System.arraycopy(new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A}, 0, png, 0, 8);
        String mediaId = uploadCloudMedia(chat.getPhoneNumberId(), png);
        GRAPH.enqueue("POST /333/media", 400, "{\"error\":{\"message\":\"(#131053) Media upload error\","
                + "\"code\":131053,\"error_data\":{\"details\":\"Unsupported file type\"}}}");

        ResponseEntity<JsonNode> res = rest.exchange(url("/api/workspaces/" + brand.getId() + "/inbox/chats/"
                + chat.getId() + "/cloud-messages"), HttpMethod.POST, auth(manager, Map.of("messaging_product",
                        "whatsapp", "type", "image", "image", Map.of("id", mediaId))), JsonNode.class);

        assertThat(res.getStatusCode()).as(String.valueOf(res.getBody())).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().toString()).contains("Unsupported file type");
        assertThat(balance()).isEqualByComparingTo("100.0000");
        assertThat(GRAPH.recorded("POST /333/messages")).isEmpty();
    }

    @Test
    void aSendMetaAcceptedKeepsItsChargeWhenRecordingFails() throws Exception {
        WaWebChat chat = cloudChat();
        plan();
        GRAPH.before("POST /333/messages", () -> mongo.updateFirst(
                new Query(Criteria.where("_id").is(chat.getPhoneNumberId())),
                new Update().set("movingSince", Instant.now()), me.nageshk.omnichannel.db.phone.PhoneNumber.class));
        GRAPH.enqueue("POST /333/messages", 200, "{\"messages\":[{\"id\":\"wamid.M1\"}]}");

        sendText(chat, "Hi");

        // Meta took it: the brand pays, whatever happened after
        assertThat(balance()).isEqualByComparingTo("99.75");
    }

    @Test
    void reinstatedTemplateIsReadAgainFromMeta() throws Exception {
        JsonNode connection = connect("111");
        addCloudNumber(connection.get("id").asString(), "Admissions", "333", "+91 98765 43210");
        String paused = CloudTemplatesIT.FOLLOW_UP.replace("\"status\":\"APPROVED\"", "\"status\":\"PAUSED\"");
        GRAPH.enqueue("GET /222/message_templates", 200, "{\"data\":[" + paused + "]}");
        meta(HttpMethod.POST, "/templates/sync", manager, Map.of());
        GRAPH.enqueue("GET /222/message_templates", 200, "{\"data\":[" + CloudTemplatesIT.FOLLOW_UP + "]}");

        webhook(keyOf(connection), envelope("{\"field\":\"message_template_status_update\",\"value\":{\"event\":"
                + "\"REINSTATED\",\"message_template_id\":\"t1\",\"message_template_name\":\"course_follow_up\","
                + "\"message_template_language\":\"en\"}}"));

        await(() -> "APPROVED".equals(meta(HttpMethod.GET, "/templates", manager, null).getBody().get(0).get("status")
                .asString()));
        assertThat(meta(HttpMethod.GET, "/templates", manager, null).getBody().get(0).get("sendable").asBoolean())
                .isTrue();
    }

    @Test
    void statusEventsAreKeptAsStatuses() throws Exception {
        JsonNode connection = connect("111");
        addCloudNumber(connection.get("id").asString(), "Admissions", "333", "+91 98765 43210");
        GRAPH.enqueue("GET /222/message_templates", 200, "{\"data\":[" + CloudTemplatesIT.FOLLOW_UP + "]}");
        meta(HttpMethod.POST, "/templates/sync", manager, Map.of());
        GRAPH.reset();

        webhook(keyOf(connection), envelope("{\"field\":\"message_template_status_update\",\"value\":{\"event\":"
                + "\"PAUSED\",\"message_template_id\":\"t1\"}}"));

        await(() -> "PAUSED".equals(meta(HttpMethod.GET, "/templates", manager, null).getBody().get(0).get("status")
                .asString()));
        // a status event is applied as it is: Meta is not asked again
        assertThat(GRAPH.recorded("GET /222/message_templates")).isEmpty();
    }

    private String uploadCloudMedia(String phoneNumberId, byte[] bytes) {
        org.springframework.http.HttpHeaders fileHeaders = new org.springframework.http.HttpHeaders();
        fileHeaders.setContentType(org.springframework.http.MediaType.IMAGE_PNG);
        fileHeaders.setContentDispositionFormData("file", "a.png");
        org.springframework.util.MultiValueMap<String, Object> form = new org.springframework.util.LinkedMultiValueMap<>();
        form.add("file", new org.springframework.http.HttpEntity<>(new org.springframework.core.io.ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return "a.png";
            }
        }, fileHeaders));
        form.add("messaging_product", "whatsapp");
        form.add("type", "image");
        org.springframework.http.HttpHeaders h = new org.springframework.http.HttpHeaders();
        h.putAll(auth(manager, null).getHeaders());
        h.setContentType(org.springframework.http.MediaType.MULTIPART_FORM_DATA);
        return rest.exchange(url("/api/workspaces/" + brand.getId() + "/channel-accounts/" + phoneNumberId + "/media"),
                HttpMethod.POST, new org.springframework.http.HttpEntity<>(form, h), JsonNode.class).getBody().get("id")
                .asString();
    }
}
