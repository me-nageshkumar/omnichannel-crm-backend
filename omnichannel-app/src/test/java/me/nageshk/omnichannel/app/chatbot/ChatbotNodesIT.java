package me.nageshk.omnichannel.app.chatbot;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import me.nageshk.omnichannel.app.automation.AutomationDevice;
import me.nageshk.omnichannel.db.chatbot.ChatbotRun;
import me.nageshk.omnichannel.db.chatbot.ChatbotRunRepository;
import me.nageshk.omnichannel.db.chatbot.ChatbotRunStatus;
import me.nageshk.omnichannel.db.chatbot.IfRunning;
import me.nageshk.omnichannel.db.chatbot.StartedBy;
import me.nageshk.omnichannel.db.contact.Contact;
import me.nageshk.omnichannel.db.contact.ContactAttribute;
import me.nageshk.omnichannel.db.contact.ContactAttributeRepository;
import me.nageshk.omnichannel.db.contact.ContactLabel;
import me.nageshk.omnichannel.db.contact.ContactLabelRepository;
import me.nageshk.omnichannel.db.contact.ContactRepository;
import me.nageshk.omnichannel.db.contact.DoNotContact;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import me.nageshk.omnichannel.service.chatbot.run.ChatbotRunService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import tools.jackson.databind.JsonNode;

/** Spec §4.2: Condition, Update contact, Do not contact and Jump nodes. */
class ChatbotNodesIT extends ChatbotTestBase {

    static final String VEENU = "919799750743@c.us";

    @Autowired ChatbotRunService runs;
    @Autowired ChatbotRunRepository runRepo;
    @Autowired ContactRepository contacts;
    @Autowired ContactAttributeRepository attributes;
    @Autowired ContactLabelRepository labels;

    private String publish(JsonNode bot) {
        assertThat(bots(HttpMethod.POST, "/" + bot.get("id").asString() + "/publish", manager, null).getStatusCode().is2xxSuccessful()).isTrue();
        return bot.get("id").asString();
    }

    private ChatbotRun start(String chatbotId, WaWebChat chat) throws Exception {
        Contact c = contacts.findById(chat.getContactId()).orElseThrow();
        ChatbotRunService.StartResult r = runs.start(brand.getId(), chatbotId, c, chat, StartedBy.test(manager.getId()),
                null, IfRunning.SKIP, true);
        assertThat(r.started()).isTrue();
        r.settled().get(5, TimeUnit.SECONDS);
        return runRepo.findById(r.run().getId()).orElseThrow();
    }

    private static Map<String, Object> rule(String field, String op, Object value) {
        Map<String, Object> l = new LinkedHashMap<>();
        l.put("field", field);
        l.put("operator", op);
        l.put("value", value);
        return l;
    }

    @SafeVarargs
    private static Map<String, Object> condition(Map<String, Object>... leaves) {
        return Map.of("combinator", "and", "rules", List.of(leaves));
    }

    private Map<String, Object> budgetFlow() {
        return flow(List.of(
                node("c", "condition", "Budget", Map.of("cases", List.of(
                        Map.of("id", "high", "label", "High", "conditions", condition(rule("{{contact.attributes.budget}}", "at_least", "5000"))),
                        Map.of("id", "low", "label", "Low", "conditions", condition(rule("{{contact.attributes.budget}}", "less_than", "5000")))))),
                node("h", "send_message", "High", textMessage("Premium")),
                node("l", "send_message", "Low", textMessage("Standard")),
                node("o", "send_message", "Other", textMessage("Unknown"))),
                List.of(edge("c", "case:high", "h"), edge("c", "case:low", "l"), edge("c", "otherwise", "o")));
    }

    private ContactAttribute attribute(String key) {
        ContactAttribute a = new ContactAttribute();
        a.setWorkspaceId(brand.getId());
        a.setName(key);
        a.setNameKey(key);
        a.setKey(key);
        return attributes.save(a);
    }

    private ContactLabel label(String name) {
        ContactLabel l = new ContactLabel();
        l.setWorkspaceId(brand.getId());
        l.setName(name);
        l.setNameKey(name.toLowerCase());
        return labels.save(l);
    }

    @Test
    void conditionPicksFirstMatchingCase() throws Exception {
        WaWebChat chat = incoming(n1, VEENU, "chat", "hello", "Veenu");
        ContactAttribute budget = attribute("budget");
        Contact c = contacts.findById(chat.getContactId()).orElseThrow();
        c.getAttributes().put(budget.getId(), "7000");
        contacts.save(c);
        String id = publish(createChatbot("Bot", budgetFlow()));
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            ChatbotRun run = start(id, chat);
            assertThat(run.getStatus()).isEqualTo(ChatbotRunStatus.COMPLETED);
            assertThat(run.getSteps().get(0).output()).isEqualTo("case:high");
            assertThat(run.getSteps().get(0).result()).containsEntry("matched", "High");
            assertThat(d.requests("sendMessage").get(0).params().get("content").asString()).isEqualTo("Premium");
        }
    }

    @Test
    void conditionOtherwise() throws Exception {
        WaWebChat chat = incoming(n1, VEENU, "chat", "hello", "Veenu");
        attribute("budget");
        String id = publish(createChatbot("Bot", budgetFlow()));
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            ChatbotRun run = start(id, chat);
            assertThat(run.getSteps().get(0).output()).isEqualTo("otherwise");
            assertThat(d.requests("sendMessage").get(0).params().get("content").asString()).isEqualTo("Unknown");
        }
    }

    @Test
    void updateContactSetsAttributesAndLabels() throws Exception {
        WaWebChat chat = incoming(n1, VEENU, "chat", "hello", "Veenu");
        ContactAttribute city = attribute("city");
        ContactLabel vip = label("VIP");
        Map<String, Object> f = flow(List.of(
                node("u", "update_contact", "Tag", Map.of("setAttributes", Map.of(city.getId(), "{{contact.name}} town"),
                        "addLabels", List.of(vip.getId()))),
                node("m", "send_message", "Say", textMessage("City {{contact.attributes.city}} {{contact.labels}}"))),
                List.of(edge("u", null, "m")));
        String id = publish(createChatbot("Bot", f));
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            start(id, chat);
            Contact c = contacts.findById(chat.getContactId()).orElseThrow();
            assertThat(c.getAttributes()).containsEntry(city.getId(), "Veenu town");
            assertThat(c.getLabels()).contains(vip.getId());
            assertThat(d.requests("sendMessage").get(0).params().get("content").asString()).isEqualTo("City Veenu town [VIP]");
        }
    }

    @Test
    void doNotContactMarksWithChatbotReason() throws Exception {
        WaWebChat chat = incoming(n1, VEENU, "chat", "hello", "Veenu");
        String id = publish(createChatbot("Bot", flow(List.of(node("d", "do_not_contact", "Opt", Map.of("mark", true))), List.of())));
        ChatbotRun run = start(id, chat);
        assertThat(run.getStatus()).isEqualTo(ChatbotRunStatus.COMPLETED);
        Contact c = contacts.findById(chat.getContactId()).orElseThrow();
        assertThat(c.getDoNotContact()).isNotNull();
        assertThat(c.getDoNotContact().reason()).isEqualTo(DoNotContact.Reason.CHATBOT);
        String clear = publish(createChatbot("Clear", flow(List.of(node("d", "do_not_contact", "Opt", Map.of("mark", false))), List.of())));
        start(clear, chat);
        assertThat(contacts.findById(chat.getContactId()).orElseThrow().getDoNotContact()).isNull();
    }

    @Test
    void jumpStartsTargetWithVariables() throws Exception {
        WaWebChat chat = incoming(n1, VEENU, "chat", "hello", "Veenu");
        String target = publish(createChatbot("Second", flow(List.of(node("s", "send_message", "Say", textMessage("You said {{AskName.answer}}"))), List.of())));
        Map<String, Object> f = flow(List.of(
                node("q", "ask_question", "AskName", Map.of("text", "Name?", "answerType", "TEXT")),
                node("j", "jump", "Go", Map.of("chatbotId", target))),
                List.of(edge("q", "next", "j")));
        String first = publish(createChatbot("First", f));
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            ChatbotRun run = start(first, chat);
            incoming(n1, VEENU, "chat", "Rahul", "Veenu");
            await(() -> d.requests("sendMessage").size() == 2);
            assertThat(d.requests("sendMessage").get(1).params().get("content").asString()).isEqualTo("You said Rahul");
            await(() -> runRepo.findById(run.getId()).orElseThrow().getStatus() == ChatbotRunStatus.JUMPED);
            await(() -> runRepo.count() == 2);
            ChatbotRun second = runRepo.findAll().stream().filter(r -> r.getChatbotId().equals(target)).findFirst().orElseThrow();
            await(() -> runRepo.findById(second.getId()).orElseThrow().getStatus() == ChatbotRunStatus.COMPLETED);
            assertThat(second.getJumps()).isEqualTo(1);
            assertThat(second.getChatId()).isEqualTo(chat.getId());
        }
    }

    @Test
    void jumpCapFails() throws Exception {
        WaWebChat chat = incoming(n1, VEENU, "chat", "hello", "Veenu");
        JsonNode a = createChatbot("A", flow(List.of(node("x", "update_contact", "X", Map.of("nameIfBlank", "a"))), List.of()));
        JsonNode b = createChatbot("B", flow(List.of(node("x", "update_contact", "X", Map.of("nameIfBlank", "b"))), List.of()));
        String aId = a.get("id").asString();
        String bId = b.get("id").asString();
        // A jumps to B, B jumps to A
        Map<String, Object> fa = flow(List.of(node("j", "jump", "ToB", Map.of("chatbotId", bId))), List.of());
        Map<String, Object> fb = flow(List.of(node("j", "jump", "ToA", Map.of("chatbotId", aId))), List.of());
        bots(HttpMethod.PUT, "/" + aId, manager, Map.of("flow", fa, "version", a.get("version").asLong()));
        bots(HttpMethod.PUT, "/" + bId, manager, Map.of("flow", fb, "version", b.get("version").asLong()));
        bots(HttpMethod.POST, "/" + aId + "/publish", manager, null);
        bots(HttpMethod.POST, "/" + bId + "/publish", manager, null);
        ChatbotRun run = start(aId, chat);
        assertThat(run.getStatus()).isEqualTo(ChatbotRunStatus.JUMPED);
        await(() -> runRepo.findAll().stream().anyMatch(r -> r.getStatus() == ChatbotRunStatus.FAILED));
        ChatbotRun failed = runRepo.findAll().stream().filter(r -> r.getStatus() == ChatbotRunStatus.FAILED).findFirst().orElseThrow();
        assertThat(failed.getEndReason()).isEqualTo("too many jumps");
        assertThat(runRepo.count()).isEqualTo(6);
    }
}
