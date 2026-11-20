package me.nageshk.omnichannel.app.meta;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import me.nageshk.omnichannel.db.automation.AutomationAction;
import me.nageshk.omnichannel.db.automation.AutomationActionType;
import me.nageshk.omnichannel.db.automation.AutomationEventKind;
import me.nageshk.omnichannel.db.automation.AutomationRun;
import me.nageshk.omnichannel.db.automation.RuleStatus;
import me.nageshk.omnichannel.db.automation.StepOutcome;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import me.nageshk.omnichannel.db.inbox.WaWebMessage;
import me.nageshk.omnichannel.service.automation.AutomationWorker;
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

/** The rule action "Send WhatsApp template" (spec 2026-11-20 §5.6). */
class SendCloudTemplateRuleIT extends MetaTestBase {

    @Autowired AutomationWorker worker;

    private String key;
    private String numberId;
    private String templateId;

    @BeforeEach
    void cloudNumberWithTemplate() throws Exception {
        mongo.remove(new Query(), "channelWebhookEvents");
        JsonNode connection = connect("111");
        key = keyOf(connection);
        numberId = addCloudNumber(connection.get("id").asString(), "Admissions", "333", "+91 98765 43210").get("id")
                .asString();
        GRAPH.enqueue("GET /222/message_templates", 200, "{\"data\":[" + CloudTemplatesIT.FOLLOW_UP + "]}");
        meta(HttpMethod.POST, "/templates/sync", manager, Map.of());
        templateId = meta(HttpMethod.GET, "/templates", manager, null).getBody().get(0).get("id").asString();
        meta(HttpMethod.PUT, "/templates/" + templateId + "/fills", manager, Map.of("fills",
                Map.of("body:1", Map.of("kind", "CONTACT_FIELD", "attributeKey", "name"))));
        GRAPH.reset();
    }

    private static AutomationAction sendCloud(String cloudTemplateId, Map<String, String> values) {
        return new AutomationAction(AutomationActionType.SEND_CLOUD_TEMPLATE, null, values, null, null, null, null, null,
                null, null, null, null, cloudTemplateId);
    }

    private Map<String, Object> ruleBody(AutomationAction action) {
        return Map.of("name", "Follow up", "event", "MESSAGE_RECEIVED", "priority", 1, "actions", List.of(
                mapper.convertValue(action, Map.class)));
    }

    @Test
    void followUpRuleSendsTemplateAfterWindow() throws Exception {
        rule("Follow up", AutomationEventKind.MESSAGE_RECEIVED, RuleStatus.PUBLISHED, 1, null,
                sendCloud(templateId, Map.of("body:2", "BCA")));
        webhook(key, envelope(textChange("333", "919876543210", "919799750743", "wamid.IN1", "Hi")));
        await(() -> !queued().isEmpty());
        WaWebChat chat = chats.findAll().stream().filter(c -> numberId.equals(c.getPhoneNumberId())).findFirst()
                .orElseThrow();
        // the follow-up runs days later: a template goes out whatever the window
        mongo.updateFirst(new Query(Criteria.where("_id").is(chat.getId())),
                new Update().set("lastInboundAt", clock.instant().minus(Duration.ofDays(3))), WaWebChat.class);
        GRAPH.enqueue("POST /333/messages", 200, "{\"messages\":[{\"id\":\"wamid.R1\"}]}");

        worker.tick();

        await(() -> !mongo.findAll(AutomationRun.class).isEmpty()
                && mongo.findAll(AutomationRun.class).get(0).getSteps().get(0).outcome() == StepOutcome.DONE);
        JsonNode body = mapper.readTree(GRAPH.recorded("POST /333/messages").get(0).body());
        assertThat(body.get("type").asString()).isEqualTo("template");
        assertThat(body.get("template").get("components").get(0).get("parameters").get(0).get("text").asString())
                .isEqualTo("Veenu");
        assertThat(body.get("template").get("components").get(0).get("parameters").get(1).get("text").asString())
                .isEqualTo("BCA");
        WaWebMessage sent = mongo.findOne(new Query(Criteria.where("waMessageId").is("wamid.R1")), WaWebMessage.class);
        assertThat(sent.getSource().name()).isEqualTo("AUTOMATION");
        assertThat(mongo.findAll(AutomationRun.class).get(0).getSteps().get(0).reason())
                .contains("course_follow_up");
    }

    @Test
    void unknownTemplateRefusedOnPublish() {
        ResponseEntity<JsonNode> res = rest.exchange(url("/api/workspaces/" + brand.getId() + "/automations/rules"),
                HttpMethod.POST, auth(manager, ruleBody(sendCloud("nope", Map.of()))), JsonNode.class);
        String id = res.getBody().get("id").asString();

        ResponseEntity<JsonNode> published = rest.exchange(url("/api/workspaces/" + brand.getId()
                + "/automations/rules/" + id + "/publish"), HttpMethod.POST, auth(manager, null), JsonNode.class);

        assertThat(published.getStatusCode().is4xxClientError()).isTrue();
        assertThat(published.getBody().toString()).contains("actions[0].cloudTemplateId");
    }

    @Test
    void wrongValueKeyRefusedOnPublish() {
        ResponseEntity<JsonNode> res = rest.exchange(url("/api/workspaces/" + brand.getId() + "/automations/rules"),
                HttpMethod.POST, auth(manager, ruleBody(sendCloud(templateId, Map.of("body:9", "x")))), JsonNode.class);
        String id = res.getBody().get("id").asString();

        ResponseEntity<JsonNode> published = rest.exchange(url("/api/workspaces/" + brand.getId()
                + "/automations/rules/" + id + "/publish"), HttpMethod.POST, auth(manager, null), JsonNode.class);

        assertThat(published.getStatusCode().is4xxClientError()).isTrue();
        assertThat(published.getBody().toString()).contains("actions[0].values.body:9");
    }

    @Test
    void whatsAppWebChatFailsStep() throws Exception {
        rule("Follow up", AutomationEventKind.MESSAGE_RECEIVED, RuleStatus.PUBLISHED, 1, null,
                sendCloud(templateId, Map.of("body:2", "BCA")));
        incoming(n1, "919811111111@c.us", "chat", "hello", "Ravi");

        worker.tick();

        await(() -> !mongo.findAll(AutomationRun.class).isEmpty());
        assertThat(mongo.findAll(AutomationRun.class).get(0).getSteps().get(0).outcome()).isEqualTo(StepOutcome.FAILED);
        assertThat(mongo.findAll(AutomationRun.class).get(0).getSteps().get(0).reason()).contains("channel not available");
    }

    @Test
    void optionsListSendableCloudTemplates() {
        JsonNode options = rest.exchange(url("/api/workspaces/" + brand.getId() + "/automations/options"),
                HttpMethod.GET, auth(manager, null), JsonNode.class).getBody();

        JsonNode t = options.get("cloudTemplates").get(0);
        assertThat(t.get("id").asString()).isEqualTo(templateId);
        assertThat(t.get("name").asString()).isEqualTo("course_follow_up");
        assertThat(t.get("phoneNumberIds").get(0).asString()).isEqualTo(numberId);
        assertThat(t.get("variables").get(0).get("key").asString()).isEqualTo("body:1");
    }

    @Test
    void ruleSummaryNamesTheTemplate() {
        rule("Follow up", AutomationEventKind.MESSAGE_RECEIVED, RuleStatus.DRAFT, 1, null,
                sendCloud(templateId, Map.of("body:2", "BCA")));

        JsonNode rules = rest.exchange(url("/api/workspaces/" + brand.getId() + "/automations/rules"), HttpMethod.GET,
                auth(manager, null), JsonNode.class).getBody();

        assertThat(rules.toString()).contains("Send WhatsApp template");
    }
}
