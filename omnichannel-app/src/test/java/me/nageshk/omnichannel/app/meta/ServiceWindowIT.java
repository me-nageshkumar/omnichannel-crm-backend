package me.nageshk.omnichannel.app.meta;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import me.nageshk.omnichannel.app.support.TestBilling;
import me.nageshk.omnichannel.common.billing.BillingPeriod;
import me.nageshk.omnichannel.common.billing.UsageMeter;
import me.nageshk.omnichannel.db.automation.AutomationAction;
import me.nageshk.omnichannel.db.automation.AutomationActionType;
import me.nageshk.omnichannel.db.automation.AutomationEventKind;
import me.nageshk.omnichannel.db.automation.AutomationRun;
import me.nageshk.omnichannel.db.automation.RuleStatus;
import me.nageshk.omnichannel.db.automation.StepOutcome;
import me.nageshk.omnichannel.db.billing.PlanTerms;
import me.nageshk.omnichannel.db.billing.Subscription;
import me.nageshk.omnichannel.db.billing.SubscriptionRepository;
import me.nageshk.omnichannel.db.billing.WalletRepository;
import me.nageshk.omnichannel.db.chatbot.ChatbotRun;
import me.nageshk.omnichannel.db.chatbot.ChatbotRunRepository;
import me.nageshk.omnichannel.db.chatbot.IfRunning;
import me.nageshk.omnichannel.db.chatbot.StartedBy;
import me.nageshk.omnichannel.db.contact.Contact;
import me.nageshk.omnichannel.db.contact.ContactRepository;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import me.nageshk.omnichannel.db.inbox.WaWebMessage;
import me.nageshk.omnichannel.security.entitlement.Entitlements;
import me.nageshk.omnichannel.service.automation.AutomationWorker;
import me.nageshk.omnichannel.service.chatbot.run.ChatbotRunService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/** Meta's 24-hour customer service window on Cloud numbers (spec 2026-11-20 §5.5). */
class ServiceWindowIT extends MetaTestBase {

    @Autowired TestBilling billing;
    @Autowired SubscriptionRepository subscriptions;
    @Autowired WalletRepository wallets;
    @Autowired Entitlements entitlements;
    @Autowired ChatbotRunService runs;
    @Autowired ChatbotRunRepository runRepo;
    @Autowired ContactRepository contacts;
    @Autowired AutomationWorker worker;

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
        await(() -> !mongo.find(new Query(Criteria.where("phoneNumberId").is(numberId)), WaWebMessage.class).isEmpty());
        chat = chats.findById(mongo.find(new Query(Criteria.where("phoneNumberId").is(numberId)), WaWebMessage.class)
                .get(0).getChatId()).orElseThrow();
        GRAPH.reset();
    }

    @AfterEach
    void noBilling() {
        billing.reset();
    }

    /** The customer last wrote {@code ago} before the app's clock. */
    private void lastInbound(Duration ago) {
        Instant at = clock.instant().minus(ago);
        mongo.updateFirst(new Query(Criteria.where("_id").is(chat.getId())), new Update().set("lastInboundAt", at),
                WaWebChat.class);
        chat = chats.findById(chat.getId()).orElseThrow();
    }

    private void plan() {
        Subscription s = billing.assign(acme.getId(), Set.of(), Map.of());
        s.getCurrent().setTerms(new PlanTerms("Gold", BillingPeriod.MONTHLY, 2, null, List.of(), List.of(), List.of(),
                Map.of(), Map.of(UsageMeter.WHATSAPP_MESSAGE, new BigDecimal("0.25"))));
        subscriptions.save(s);
        entitlements.evict(acme.getId());
        billing.wallet(acme.getId(), "100.0000");
    }

    private ResponseEntity<JsonNode> sendText(String text) {
        return rest.exchange(url("/api/workspaces/" + brand.getId() + "/inbox/chats/" + chat.getId() + "/messages"),
                HttpMethod.POST, auth(manager, Map.of("text", text)), JsonNode.class);
    }

    @Test
    void chatViewShowsWindowForCloudOnly() throws Exception {
        lastInbound(Duration.ofHours(2));
        JsonNode view = rest.exchange(url("/api/workspaces/" + brand.getId() + "/inbox/chats/" + chat.getId()),
                HttpMethod.GET, auth(manager, null), JsonNode.class).getBody();
        assertThat(view.get("channel").asString()).isEqualTo("WHATSAPP_CLOUD");
        assertThat(view.get("window").get("open").asBoolean()).isTrue();
        assertThat(Instant.parse(view.get("window").get("closesAt").asString()))
                .isEqualTo(chat.getLastInboundAt().plus(Duration.ofHours(24)));

        WaWebChat web = incoming(n1, "919811111111@c.us", "chat", "hello", "Ravi");
        JsonNode webView = rest.exchange(url("/api/workspaces/" + brand.getId() + "/inbox/chats/" + web.getId()),
                HttpMethod.GET, auth(manager, null), JsonNode.class).getBody();
        assertThat(webView.get("channel").asString()).isEqualTo("WHATSAPP_WEB");
        assertThat(webView.get("window").isNull()).isTrue();
    }

    @Test
    void freeTextAfterWindowRefusedBeforeCharge() {
        plan();
        lastInbound(Duration.ofHours(25));

        ResponseEntity<JsonNode> res = sendText("Following up");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(res.getBody().get("code").asString()).isEqualTo("WINDOW_CLOSED");
        assertThat(Instant.parse(res.getBody().get("closesAt").asString()))
                .isEqualTo(chat.getLastInboundAt().plus(Duration.ofHours(24)));
        assertThat(GRAPH.recorded("POST /333/messages")).isEmpty();
        assertThat(wallets.findByCustomerId(acme.getId()).orElseThrow().getBalance()).isEqualByComparingTo("100.0000");
    }

    @Test
    void metaWindowErrorMapsToWindowClosed() {
        plan();
        lastInbound(Duration.ofHours(1));
        GRAPH.enqueue("POST /333/messages", 400, "{\"error\":{\"message\":\"Re-engagement message\",\"code\":131047}}");

        ResponseEntity<JsonNode> res = sendText("Hi");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(res.getBody().get("code").asString()).isEqualTo("WINDOW_CLOSED");
        assertThat(wallets.findByCustomerId(acme.getId()).orElseThrow().getBalance()).isEqualByComparingTo("100.0000");
    }

    @Test
    void newChatWithTextOnCloudIsWindowClosed() {
        ResponseEntity<JsonNode> res = rest.exchange(url("/api/workspaces/" + brand.getId() + "/inbox/phone-numbers/"
                + numberId + "/chats"), HttpMethod.POST, auth(manager, Map.of("phone", "919811122233", "text", "Hello")),
                JsonNode.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(res.getBody().get("code").asString()).isEqualTo("WINDOW_CLOSED");
        assertThat(GRAPH.recorded("POST /333/messages")).isEmpty();
    }

    @Test
    void chatbotSendAfterWindowTakesFailure() throws Exception {
        lastInbound(Duration.ofHours(25));
        JsonNode bot = createChatbot("Late bot", flow(List.of(node("m", "send_message", "Hello", textMessage("Hello"))),
                List.of()));
        Contact c = contacts.findById(chat.getContactId()).orElseThrow();

        ChatbotRunService.StartResult r = runs.start(brand.getId(), bot.get("id").asString(), c, chat,
                StartedBy.test(manager.getId()), null, IfRunning.SKIP, true);
        r.settled().get(5, TimeUnit.SECONDS);

        ChatbotRun run = runRepo.findById(r.run().getId()).orElseThrow();
        assertThat(run.getSteps().get(0).summary() + " " + run.getSteps().get(0).error()).containsIgnoringCase("24-hour window");
        assertThat(GRAPH.recorded("POST /333/messages")).isEmpty();
    }

    @Test
    void ruleSavedMessageAfterWindowRecordsReason() throws Exception {
        lastInbound(Duration.ofHours(25));
        String saved = template("Follow up", "Following up on your enquiry");
        rule("Follow up", AutomationEventKind.MESSAGE_RECEIVED, RuleStatus.PUBLISHED, 1, null,
                new AutomationAction(AutomationActionType.SEND_TEMPLATE, saved, Map.of(), null, null, null, null, null,
                        null, null, null, null));

        // a new customer message opens the window again, so the event is made by hand on the old chat
        webhook(key, envelope(textChange("333", "919876543210", "919799750743", "wamid.IN2", "still there?")));
        await(() -> !queued().isEmpty());
        lastInbound(Duration.ofHours(25));
        worker.tick();

        await(() -> !mongo.findAll(AutomationRun.class).isEmpty());
        AutomationRun run = mongo.findAll(AutomationRun.class).get(0);
        assertThat(run.getSteps().get(0).outcome()).isEqualTo(StepOutcome.FAILED);
        assertThat(run.getSteps().get(0).reason()).containsIgnoringCase("24-hour window");
    }
}
