package me.nageshk.omnichannel.app.meta;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import me.nageshk.omnichannel.app.automation.AutomationDevice;
import me.nageshk.omnichannel.app.support.GraphStub;
import me.nageshk.omnichannel.app.support.TestBilling;
import me.nageshk.omnichannel.common.billing.BillingPeriod;
import me.nageshk.omnichannel.common.billing.UsageMeter;
import me.nageshk.omnichannel.db.billing.PlanTerms;
import me.nageshk.omnichannel.db.billing.Subscription;
import me.nageshk.omnichannel.db.billing.SubscriptionRepository;
import me.nageshk.omnichannel.db.billing.WalletRepository;
import me.nageshk.omnichannel.db.chatbot.ChatbotRun;
import me.nageshk.omnichannel.db.chatbot.ChatbotRunRepository;
import me.nageshk.omnichannel.db.chatbot.ChatbotRunStatus;
import me.nageshk.omnichannel.db.chatbot.IfRunning;
import me.nageshk.omnichannel.db.chatbot.StartedBy;
import me.nageshk.omnichannel.db.contact.Contact;
import me.nageshk.omnichannel.db.contact.ContactRepository;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import me.nageshk.omnichannel.db.inbox.WaWebMessage;
import me.nageshk.omnichannel.security.entitlement.Entitlements;
import me.nageshk.omnichannel.service.chatbot.run.ChatbotRunService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/** Cloud numbers send through Meta's API (spec 2026-11-20 §5.4); WhatsApp Web numbers still use their device. */
class CloudSendIT extends MetaTestBase {

    @Autowired TestBilling billing;
    @Autowired SubscriptionRepository subscriptions;
    @Autowired WalletRepository wallets;
    @Autowired Entitlements entitlements;
    @Autowired ChatbotRunService runs;
    @Autowired ChatbotRunRepository runRepo;
    @Autowired ContactRepository contacts;

    private String key;
    private String numberId;
    private WaWebChat chat;

    @BeforeEach
    void cloudChat() throws Exception {
        mongo.remove(new Query(), "channelWebhookEvents");
        JsonNode connection = connect("111");
        key = keyOf(connection);
        numberId = addCloudNumber(connection.get("id").asString(), "Admissions", "333", "+91 98765 43210").get("id")
                .asString();
        webhook(key, envelope(textChange("333", "919876543210", "919799750743", "wamid.IN1", "Admission enquiry")));
        await(() -> !messages().isEmpty());
        chat = chats.findById(messages().get(0).getChatId()).orElseThrow();
        GRAPH.reset();
    }

    @AfterEach
    void noBilling() {
        billing.reset();
    }

    private List<WaWebMessage> messages() {
        return mongo.find(new Query(Criteria.where("phoneNumberId").is(numberId)), WaWebMessage.class);
    }

    private WaWebMessage byWamid(String wamid) {
        return messages().stream().filter(m -> wamid.equals(m.getWaMessageId())).findFirst().orElse(null);
    }

    private static String sent(String wamid) {
        return "{\"messaging_product\":\"whatsapp\",\"contacts\":[{\"input\":\"919799750743\",\"wa_id\":\"919799750743\"}],"
                + "\"messages\":[{\"id\":\"" + wamid + "\"}]}";
    }

    private ResponseEntity<JsonNode> sendText(String text) {
        return rest.exchange(url("/api/workspaces/" + brand.getId() + "/inbox/chats/" + chat.getId() + "/messages"),
                HttpMethod.POST, auth(manager, Map.of("text", text)), JsonNode.class);
    }

    private void plan(String rate) {
        Subscription s = billing.assign(acme.getId(), Set.of(), Map.of());
        s.getCurrent().setTerms(new PlanTerms("Gold", BillingPeriod.MONTHLY, 2, null, List.of(), List.of(), List.of(),
                Map.of(), Map.of(UsageMeter.WHATSAPP_MESSAGE, new BigDecimal(rate))));
        subscriptions.save(s);
        entitlements.evict(acme.getId());
        billing.wallet(acme.getId(), "100.0000");
    }

    private BigDecimal balance() {
        return wallets.findByCustomerId(acme.getId()).orElseThrow().getBalance();
    }

    @Test
    void typedReplyGoesToMeta() throws Exception {
        GRAPH.enqueue("POST /333/messages", 200, sent("wamid.OUT1"));

        ResponseEntity<JsonNode> res = sendText("Hi");

        assertThat(res.getStatusCode()).as(String.valueOf(res.getBody())).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody().get("waMessageId").asString()).isEqualTo("wamid.OUT1");
        GraphStub.Recorded r = GRAPH.recorded("POST /333/messages").get(0);
        assertThat(r.authorization()).isEqualTo("Bearer " + TOKEN);
        assertThat(r.body()).isEqualTo("{\"messaging_product\":\"whatsapp\",\"recipient_type\":\"individual\","
                + "\"to\":\"919799750743\",\"type\":\"text\",\"text\":{\"preview_url\":true,\"body\":\"Hi\"}}");
        assertThat(byWamid("wamid.OUT1").getBody()).isEqualTo("Hi");
        // the customer's last message is marked read, as the bridge's sendSeen does
        await(() -> GRAPH.recorded("POST /333/messages").stream().anyMatch(x -> x.body().contains("\"status\":\"read\"")));
        assertThat(GRAPH.recorded("POST /333/messages").stream().filter(x -> x.body().contains("\"status\":\"read\""))
                .findFirst().orElseThrow().body()).contains("\"message_id\":\"wamid.IN1\"");
    }

    @Test
    void statusBeforeRowIsApplied() throws Exception {
        GRAPH.beforeNext(() -> {
            // Meta's statuses beat our send's answer
            webhook(key, envelope("{\"field\":\"messages\",\"value\":{\"messaging_product\":\"whatsapp\",\"metadata\":"
                    + "{\"display_phone_number\":\"919876543210\",\"phone_number_id\":\"333\"},\"statuses\":["
                    + "{\"id\":\"wamid.OUT2\",\"status\":\"sent\",\"timestamp\":\"" + Instant.now().getEpochSecond()
                    + "\",\"recipient_id\":\"919799750743\"},{\"id\":\"wamid.OUT2\",\"status\":\"delivered\","
                    + "\"timestamp\":\"" + Instant.now().getEpochSecond() + "\",\"recipient_id\":\"919799750743\"}]}}"));
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        GRAPH.enqueue("POST /333/messages", 200, sent("wamid.OUT2"));

        assertThat(sendText("Hello").getStatusCode()).isEqualTo(HttpStatus.OK);

        await(() -> byWamid("wamid.OUT2") != null && byWamid("wamid.OUT2").getStatusTimes().getDeliveredAt() != null);
        assertThat(byWamid("wamid.OUT2").getStatusTimes().getSentAt()).isNotNull();
    }

    @Test
    void chatbotButtonsGoNative() throws Exception {
        Map<String, Object> ask = Map.of("message", Map.of("type", "interactive", "interactive", Map.of("type", "button",
                "body", Map.of("text", "Book a demo?"), "action", Map.of("buttons", List.of(
                        Map.of("type", "reply", "reply", Map.of("id", "yes", "title", "Yes")),
                        Map.of("type", "reply", "reply", Map.of("id", "no", "title", "No")))))));
        JsonNode bot = createChatbot("Course enquiry", flow(List.of(
                node("ask", "send_message", "Ask", ask),
                node("yes", "send_message", "Yes", textMessage("Great")),
                node("no", "send_message", "No", textMessage("Okay"))),
                List.of(edge("ask", "reply:yes", "yes"), edge("ask", "reply:no", "no"))));
        GRAPH.enqueue("POST /333/messages", 200, sent("wamid.ASK"));
        Contact c = contacts.findById(chat.getContactId()).orElseThrow();

        ChatbotRunService.StartResult r = runs.start(brand.getId(), bot.get("id").asString(), c, chat,
                StartedBy.test(manager.getId()), null, IfRunning.SKIP, true);
        r.settled().get(5, TimeUnit.SECONDS);

        JsonNode body = mapper.readTree(GRAPH.recorded("POST /333/messages").get(0).body());
        assertThat(body.get("type").asString()).isEqualTo("interactive");
        assertThat(body.get("interactive").get("action").get("buttons").get(0).get("reply").get("id").asString())
                .isEqualTo("yes");
        assertThat(mongo.getCollection("messagePages").countDocuments()).isZero();

        GRAPH.enqueue("POST /333/messages", 200, sent("wamid.GREAT"));
        webhook(key, envelope("{\"field\":\"messages\",\"value\":{\"messaging_product\":\"whatsapp\",\"metadata\":"
                + "{\"display_phone_number\":\"919876543210\",\"phone_number_id\":\"333\"},\"contacts\":[{\"profile\":"
                + "{\"name\":\"Veenu\"},\"wa_id\":\"919799750743\"}],\"messages\":[{\"context\":{\"from\":\"919876543210\","
                + "\"id\":\"wamid.ASK\"},\"from\":\"919799750743\",\"id\":\"wamid.TAP\",\"timestamp\":\""
                + Instant.now().getEpochSecond() + "\",\"type\":\"interactive\",\"interactive\":{\"type\":\"button_reply\","
                + "\"button_reply\":{\"id\":\"yes\",\"title\":\"Yes\"}}}]}}"));

        await(() -> runRepo.findById(r.run().getId()).map(ChatbotRun::getStatus).orElse(null)
                == ChatbotRunStatus.COMPLETED);
        assertThat(GRAPH.recorded("POST /333/messages").stream().anyMatch(x -> x.body().contains("Great"))).isTrue();
    }

    @Test
    void pollSavedMessageRefusedOnCloud() {
        plan("0.25");
        String poll = template("Which course?", "x");
        mongo.updateFirst(new Query(Criteria.where("_id").is(poll)), new org.springframework.data.mongodb.core.query.Update()
                .set("type", "POLL").set("content.question", "Which course?").set("content.options", List.of("BCA", "MBA")),
                "messageTemplates");

        ResponseEntity<JsonNode> res = rest.exchange(url("/api/workspaces/" + brand.getId() + "/inbox/chats/"
                + chat.getId() + "/messages"), HttpMethod.POST, auth(manager, Map.of("templateId", poll)), JsonNode.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().toString()).contains("not available on Cloud API numbers");
        assertThat(balance()).isEqualByComparingTo("100.0000");
        assertThat(GRAPH.recorded("POST /333/messages")).isEmpty();
    }

    @Test
    void revokedTokenStopsSendsWithoutCharge() {
        plan("0.25");
        GRAPH.enqueue("POST /333/messages", 401, "{\"error\":{\"message\":\"Error validating access token\",\"code\":190}}");

        ResponseEntity<JsonNode> first = sendText("Hi");

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(first.getBody().get("code").asString()).isEqualTo("CLOUD_NOT_READY");
        assertThat(balance()).isEqualByComparingTo("100.0000");
        assertThat(meta(HttpMethod.GET, "/connections", manager, null).getBody().get(0).get("status").asString())
                .isEqualTo("INVALID");
        GRAPH.reset();

        ResponseEntity<JsonNode> second = sendText("Hi again");

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(GRAPH.recorded()).isEmpty();
        assertThat(balance()).isEqualByComparingTo("100.0000");
    }

    @Test
    void metaRefusalIsShownAndNotCharged() {
        plan("0.25");
        GRAPH.enqueue("POST /333/messages", 400, "{\"error\":{\"message\":\"(#100) Invalid parameter\",\"code\":100,"
                + "\"error_data\":{\"details\":\"Text body too long\"}}}");

        ResponseEntity<JsonNode> res = sendText("Hi");

        assertThat(res.getStatusCode().is4xxClientError() || res.getStatusCode().is5xxServerError()).isTrue();
        assertThat(res.getBody().toString()).contains("Text body too long");
        assertThat(balance()).isEqualByComparingTo("100.0000");
    }

    @Test
    void rateLimitIsRetryableForChatbot() throws Exception {
        JsonNode bot = createChatbot("Hello bot", flow(List.of(node("m", "send_message", "Hello", textMessage("Hello"))),
                List.of()));
        GRAPH.enqueue("POST /333/messages", 400, "{\"error\":{\"message\":\"Too many messages\",\"code\":130429}}");
        Contact c = contacts.findById(chat.getContactId()).orElseThrow();

        ChatbotRunService.StartResult r = runs.start(brand.getId(), bot.get("id").asString(), c, chat,
                StartedBy.test(manager.getId()), null, IfRunning.SKIP, true);
        r.settled().get(5, TimeUnit.SECONDS);

        ChatbotRun run = runRepo.findById(r.run().getId()).orElseThrow();
        assertThat(run.getStatus()).isNotEqualTo(ChatbotRunStatus.FAILED);
        assertThat(run.getStatus()).isNotEqualTo(ChatbotRunStatus.COMPLETED);
    }

    @Test
    void laterFailedStatusRefunds() throws Exception {
        plan("0.25");
        GRAPH.enqueue("POST /333/messages", 200, sent("wamid.F1"));
        assertThat(sendText("Hi").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(balance()).isEqualByComparingTo("99.75");
        String failed = envelope("{\"field\":\"messages\",\"value\":{\"messaging_product\":\"whatsapp\",\"metadata\":"
                + "{\"display_phone_number\":\"919876543210\",\"phone_number_id\":\"333\"},\"statuses\":[{\"id\":"
                + "\"wamid.F1\",\"status\":\"failed\",\"timestamp\":\"" + Instant.now().getEpochSecond()
                + "\",\"recipient_id\":\"919799750743\",\"errors\":[{\"code\":131026,\"title\":\"Message undeliverable\"}]}]}}");

        webhook(key, failed);
        await(() -> balance().compareTo(new BigDecimal("100.0000")) == 0);
        webhook(key, failed.replace("\"timestamp\":\"", "\"timestamp\":\"1"));
        Thread.sleep(400);

        assertThat(balance()).isEqualByComparingTo("100.0000");
    }

    @Test
    void cloudShapedApiAcceptsWhatsappProduct() {
        GRAPH.enqueue("POST /333/messages", 200, sent("wamid.API"));
        String base = "/api/workspaces/" + brand.getId() + "/channel-accounts/";

        ResponseEntity<JsonNode> cloud = rest.exchange(url(base + numberId + "/messages"), HttpMethod.POST,
                auth(manager, Map.of("messaging_product", "whatsapp", "to", "919799750743", "type", "text",
                        "text", Map.of("body", "From the API"))), JsonNode.class);
        assertThat(cloud.getStatusCode()).as(String.valueOf(cloud.getBody())).isEqualTo(HttpStatus.OK);
        assertThat(cloud.getBody().get("messaging_product").asString()).isEqualTo("whatsapp");

        ResponseEntity<JsonNode> wrong = rest.exchange(url(base + numberId + "/messages"), HttpMethod.POST,
                auth(manager, Map.of("messaging_product", "whatsapp-web", "to", "919799750743", "type", "text",
                        "text", Map.of("body", "x"))), JsonNode.class);
        assertThat(wrong.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<JsonNode> webWithCloudProduct = rest.exchange(url(base + n1.getId() + "/messages"),
                HttpMethod.POST, auth(manager, Map.of("messaging_product", "whatsapp", "to", "919799750743",
                        "type", "text", "text", Map.of("body", "x"))), JsonNode.class);
        assertThat(webWithCloudProduct.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void whatsAppWebNumberUnchanged() throws Exception {
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            WaWebChat web = incoming(n1, "919811111111@c.us", "chat", "hello", "Ravi");
            rest.exchange(url("/api/workspaces/" + brand.getId() + "/inbox/chats/" + web.getId() + "/messages"),
                    HttpMethod.POST, auth(manager, Map.of("text", "Hi Ravi")), JsonNode.class);
            assertThat(d.requests("sendMessage")).hasSize(1);
        }
        assertThat(GRAPH.recorded()).isEmpty();
    }
}
