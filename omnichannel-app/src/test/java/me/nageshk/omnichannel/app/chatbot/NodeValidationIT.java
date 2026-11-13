package me.nageshk.omnichannel.app.chatbot;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import me.nageshk.omnichannel.app.support.TestUsers;
import me.nageshk.omnichannel.db.contact.ContactAttribute;
import me.nageshk.omnichannel.db.contact.ContactAttributeRepository;
import me.nageshk.omnichannel.db.template.MessageTemplate;
import me.nageshk.omnichannel.db.template.MessageTemplateRepository;
import me.nageshk.omnichannel.db.template.TemplateStatus;
import me.nageshk.omnichannel.db.template.TemplateType;
import me.nageshk.omnichannel.db.tenant.Customer;
import me.nageshk.omnichannel.db.tenant.Workspace;
import me.nageshk.omnichannel.service.chatbot.FlowValidator;
import me.nageshk.omnichannel.service.chatbot.flow.FlowParser;
import me.nageshk.omnichannel.service.chatbot.node.Problem;
import me.nageshk.omnichannel.service.chatbot.node.Severity;
import me.nageshk.omnichannel.service.chatbot.node.ValidationContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** Spec §6: each node type's executor validates its own settings against the brand's data. */
@SpringBootTest
class NodeValidationIT {

    @Autowired TestUsers testUsers;
    @Autowired FlowValidator validator;
    @Autowired FlowParser parser;
    @Autowired MessageTemplateRepository templates;
    @Autowired ContactAttributeRepository attributes;

    Customer acme;
    Workspace brand;

    @BeforeEach
    void setUp() {
        testUsers.deleteAll();
        acme = testUsers.customer("Acme");
        brand = testUsers.workspace(acme.getId(), "Brand A");
    }

    @AfterEach
    void tearDown() {
        testUsers.deleteAll();
    }

    private List<Problem> one(String type, Map<String, Object> settings) {
        Map<String, Object> data = new HashMap<>();
        data.put("name", "Node1");
        data.put("settings", settings);
        Map<String, Object> n = new HashMap<>();
        n.put("id", "n1");
        n.put("type", type);
        n.put("data", data);
        Map<String, Object> f = new HashMap<>();
        f.put("nodes", List.of(n));
        f.put("edges", List.of());
        // the node's own rules only: the flow-level "waits for X, which nothing answers" guard is FlowValidatorTest's
        return validator.validate(parser.parse(f), new ValidationContext(brand.getId(), "bot-1", false)).stream()
                .filter(p -> p.severity() == Severity.ERROR && !p.message().startsWith("waits for ")).toList();
    }

    private static Map<String, Object> buttonMessage(String title) {
        return Map.of("type", "interactive", "interactive", Map.of("type", "button", "body", Map.of("text", "Hi"),
                "action", Map.of("buttons", List.of(Map.of("type", "reply", "reply", Map.of("id", "yes", "title", title))))));
    }

    @Test
    void sendMessageMapsCloudPaths() {
        List<Problem> p = one("send_message", Map.of("message", buttonMessage("A title that is far too long for a button")));
        assertThat(p).extracting(Problem::field).contains("settings.message.interactive.action.buttons.0.reply.title");
    }

    @Test
    void sendMessageWarnsWhenNoButtonConnected() {
        Map<String, Object> data = new HashMap<>();
        data.put("name", "Node1");
        data.put("settings", Map.of("message", buttonMessage("Yes")));
        Map<String, Object> n = new HashMap<>(Map.of("id", "n1", "type", "send_message", "data", data));
        Map<String, Object> f = new HashMap<>(Map.of("nodes", List.of(n), "edges", List.of()));
        List<Problem> all = validator.validate(parser.parse(f), new ValidationContext(brand.getId(), "bot-1", false));
        assertThat(all).filteredOn(x -> x.severity() == Severity.WARNING).extracting(Problem::field).contains("settings.message");
        assertThat(all).filteredOn(x -> x.severity() == Severity.ERROR).isEmpty();
    }

    @Test
    void sendTemplateRefusesArchived() {
        MessageTemplate t = new MessageTemplate();
        t.setWorkspaceId(brand.getId());
        t.setCustomerId(acme.getId());
        t.setName("Old");
        t.setType(TemplateType.TEXT);
        t.setStatus(TemplateStatus.ARCHIVED);
        t.setPlaceholders(List.of());
        t.setCreationTime(Instant.now());
        t = templates.save(t);
        assertThat(one("send_template", Map.of("templateId", t.getId()))).extracting(Problem::field)
                .contains("settings.templateId");
        assertThat(one("send_template", Map.of("templateId", "nope"))).extracting(Problem::field)
                .contains("settings.templateId");
    }

    @Test
    void askQuestionSaveAsPattern() {
        assertThat(one("ask_question", Map.of("text", "Your budget?", "answerType", "NUMBER", "saveAs", "my budget")))
                .extracting(Problem::field).contains("settings.saveAs");
        assertThat(one("ask_question", Map.of("answerType", "NUMBER"))).extracting(Problem::field).contains("settings.text");
        assertThat(one("ask_question", Map.of("text", "Hi", "answerType", "TEXT", "retries", 9))).extracting(Problem::field)
                .contains("settings.retries");
        assertThat(one("ask_question", Map.of("text", "Hi", "answerType", "TEXT", "attributeId", "nope")))
                .extracting(Problem::field).contains("settings.attributeId");
        assertThat(one("ask_question", Map.of("text", "Hi", "answerType", "TEXT"))).isEmpty();
    }

    @Test
    void conditionNeedsCompleteRules() {
        assertThat(one("condition", Map.of("cases", List.of()))).extracting(Problem::field).contains("settings.cases");
        Map<String, Object> leaf = Map.of("field", "{{AskBudget.answer}}", "operator", "at_least");
        Map<String, Object> group = Map.of("combinator", "and", "rules", List.of(leaf));
        assertThat(one("condition", Map.of("cases", List.of(Map.of("id", "c1", "label", "High", "conditions", group)))))
                .extracting(Problem::field).contains("settings.cases[0].conditions.rules[0].value");
        Map<String, Object> ok = Map.of("field", "{{AskBudget.answer}}", "operator", "at_least", "value", "5000");
        assertThat(one("condition", Map.of("cases", List.of(Map.of("id", "c1", "label", "High", "conditions",
                Map.of("combinator", "and", "rules", List.of(ok))))))).isEmpty();
    }

    @Test
    void updateContactNeedsAChange() {
        assertThat(one("update_contact", Map.of())).extracting(Problem::field).contains("settings");
        ContactAttribute a = new ContactAttribute();
        a.setWorkspaceId(brand.getId());
        a.setName("City");
        a.setNameKey("city");
        a.setKey("city");
        a = attributes.save(a);
        assertThat(one("update_contact", Map.of("setAttributes", Map.of(a.getId(), "Pune")))).isEmpty();
        assertThat(one("update_contact", Map.of("setAttributes", Map.of("nope", "Pune")))).extracting(Problem::field)
                .contains("settings.setAttributes.nope");
        assertThat(one("update_contact", Map.of("addLabels", List.of("nope")))).extracting(Problem::field)
                .contains("settings.addLabels[0]");
    }

    @Test
    void assignChatUnknownAgent() {
        assertThat(one("assign_chat", Map.of("mode", "AGENT", "userId", "nope"))).extracting(Problem::field)
                .contains("settings.userId");
        assertThat(one("assign_chat", Map.of("mode", "TEAM_QUEUE"))).extracting(Problem::field).contains("settings.teamId");
        assertThat(one("assign_chat", Map.of())).extracting(Problem::field).contains("settings.mode");
    }

    @Test
    void addToCrmWhenCrmOff() {
        assertThat(one("add_to_crm", Map.of())).extracting(Problem::field).contains("settings.pipelineId");
    }

    @Test
    void httpRequestBadUrl() {
        assertThat(one("http_request", Map.of("method", "GET", "url", "ftp://x"))).extracting(Problem::field)
                .contains("settings.url");
        assertThat(one("http_request", Map.of("method", "FETCH", "url", "https://x.example"))).extracting(Problem::field)
                .contains("settings.method");
        assertThat(one("http_request", Map.of("method", "POST", "url", "https://x.example", "contentType",
                "application/json", "body", "{not json"))).extracting(Problem::field).contains("settings.body");
        assertThat(one("http_request", Map.of("method", "POST", "url", "https://x.example", "contentType",
                "application/json", "body", "{\"a\":\"{{Q.answer}}\"}"))).isEmpty();
    }

    @Test
    void httpRequestHeaderNewline() {
        assertThat(one("http_request", Map.of("method", "GET", "url", "https://x.example",
                "headers", Map.of("X-A", "a\r\nb")))).extracting(Problem::field).contains("settings.headers.X-A");
    }

    @Test
    void waitOutOfRange() {
        assertThat(one("wait", Map.of("amount", 31, "unit", "DAYS"))).extracting(Problem::field).contains("settings.amount");
        assertThat(one("wait", Map.of("amount", 0, "unit", "MINUTES"))).extracting(Problem::field).contains("settings.amount");
        assertThat(one("wait", Map.of("amount", 2, "unit", "HOURS"))).isEmpty();
    }

    @Test
    void jumpUnknownChatbot() {
        assertThat(one("jump", Map.of("chatbotId", "nope"))).extracting(Problem::field).contains("settings.chatbotId");
    }

    @Test
    void askFormNeedsLinkPlaceholder() {
        Map<String, Object> field = Map.of("key", "city", "label", "City", "type", "TEXT", "required", true);
        assertThat(one("ask_form", Map.of("title", "Details", "fields", List.of(field), "linkMessage", "Fill this")))
                .extracting(Problem::field).contains("settings.linkMessage");
        assertThat(one("ask_form", Map.of("title", "Details", "fields", List.of(field), "linkMessage",
                "Fill this: {{form.url}}"))).isEmpty();
    }

    @Test
    void askFormDuplicateKey() {
        Map<String, Object> f1 = Map.of("key", "city", "label", "City", "type", "TEXT", "required", true);
        Map<String, Object> f2 = Map.of("key", "city", "label", "Town", "type", "TEXT", "required", false);
        assertThat(one("ask_form", Map.of("title", "Details", "fields", List.of(f1, f2), "linkMessage", "{{form.url}}")))
                .extracting(Problem::field).contains("settings.fields[1].key");
        Map<String, Object> choice = Map.of("key", "size", "label", "Size", "type", "CHOICE", "required", true,
                "options", List.of("S"));
        assertThat(one("ask_form", Map.of("title", "Details", "fields", List.of(choice), "linkMessage", "{{form.url}}")))
                .extracting(Problem::field).contains("settings.fields[0].options");
    }
}
