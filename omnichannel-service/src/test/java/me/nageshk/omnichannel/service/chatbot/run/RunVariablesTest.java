package me.nageshk.omnichannel.service.chatbot.run;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.nageshk.omnichannel.db.automation.EventData;
import me.nageshk.omnichannel.db.chatbot.StartedBy;
import me.nageshk.omnichannel.db.contact.Contact;
import me.nageshk.omnichannel.db.contact.ContactAttribute;
import me.nageshk.omnichannel.db.contact.ContactLabel;
import me.nageshk.omnichannel.db.inbox.ChatStatus;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import me.nageshk.omnichannel.db.phone.PhoneNumber;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Spec §4.4: what a run knows, as nested maps for Mustache and flattened for the condition evaluator. */
class RunVariablesTest {

    private final RunVariables variables = new RunVariables(JsonMapper.builder().build());
    private static final Instant NOW = Instant.parse("2026-11-13T10:00:00Z");

    static Contact contact() {
        Contact c = new Contact();
        c.setId("k1");
        c.setName("Veenu");
        c.setPhone("919799750743");
        c.getAttributes().put("attr-city", "Pune");
        c.setLabels(Set.of("lab-vip"));
        return c;
    }

    static ContactAttribute attribute(String id, String key) {
        ContactAttribute a = new ContactAttribute();
        a.setId(id);
        a.setKey(key);
        a.setName(key);
        return a;
    }

    static ContactLabel label(String id, String name) {
        ContactLabel l = new ContactLabel();
        l.setId(id);
        l.setName(name);
        return l;
    }

    static WaWebChat chat() {
        WaWebChat c = new WaWebChat();
        c.setId("ch1");
        c.setStatus(ChatStatus.OPEN);
        c.setAssignedTeamId("team-1");
        return c;
    }

    static PhoneNumber number() {
        PhoneNumber n = new PhoneNumber();
        n.setNumber("919800000001");
        n.setLabel("Main line");
        return n;
    }

    static EventData event() {
        return new EventData(new EventData.ChatData("ch1", true, ChatStatus.OPEN, null, null, false),
                new EventData.MessageData("m1", "price?", "text", null),
                new EventData.NumberData("n1", "919800000001", "Main line"),
                new EventData.ContactData("k1", "Veenu", "919799750743", false, false), null, null, null, null);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> at(Map<String, Object> m, String key) {
        return (Map<String, Object>) m.get(key);
    }

    @Test
    void buildsContactChatNumberEventTrigger() {
        Map<String, Object> v = variables.initial(contact(), List.of(attribute("attr-city", "city")),
                List.of(label("lab-vip", "VIP")), chat(), number(), event(),
                new StartedBy("r1", "Welcome", "e1", null), NOW);
        assertThat(at(at(v, "contact"), "attributes")).containsEntry("city", "Pune");
        assertThat(at(v, "contact")).containsEntry("name", "Veenu").containsEntry("phone", "919799750743")
                .containsEntry("doNotContact", false);
        assertThat(at(v, "contact").get("labels")).isEqualTo(List.of("VIP"));
        assertThat(at(v, "chat")).containsEntry("id", "ch1").containsEntry("status", "OPEN")
                .containsEntry("assignedTeam", "team-1");
        assertThat(at(v, "number")).containsEntry("label", "Main line").containsEntry("phone", "919800000001");
        assertThat(at(at(v, "event"), "message")).containsEntry("text", "price?");
        assertThat(at(at(v, "event"), "chat")).containsEntry("new", true);
        assertThat(at(v, "trigger")).containsEntry("ruleName", "Welcome").containsEntry("eventId", "e1")
                .containsEntry("at", NOW.toString());
        assertThat(v).containsEntry("now", NOW.toString());
    }

    @Test
    void flattenUsesDottedPaths() {
        Map<String, Object> flat = RunVariables.flatten(Map.of("AskBudget", Map.of("answer", 5000),
                "contact", Map.of("labels", List.of("VIP"), "attributes", Map.of("city", "Pune"))));
        assertThat(flat).containsEntry("AskBudget.answer", 5000).containsEntry("contact.attributes.city", "Pune")
                .containsEntry("contact.labels", List.of("VIP"));
    }

    @Test
    void emptyEventWhenStartedByTest() {
        Map<String, Object> v = variables.initial(contact(), List.of(), List.of(), null, null, null,
                StartedBy.test("u1"), NOW);
        assertThat(at(v, "event")).isEmpty();
        assertThat(at(v, "trigger")).containsEntry("ruleName", "Test");
        assertThat(at(v, "chat")).isEmpty();
    }

    @Test
    void refreshContactReplacesTheContactBranch() {
        Map<String, Object> v = variables.initial(contact(), List.of(), List.of(), null, null, null,
                StartedBy.test("u1"), NOW);
        Contact c = contact();
        c.setName("Veenu K");
        variables.refreshContact(v, c, List.of(attribute("attr-city", "city")), List.of());
        assertThat(at(v, "contact")).containsEntry("name", "Veenu K");
        assertThat(at(at(v, "contact"), "attributes")).containsEntry("city", "Pune");
    }
}
