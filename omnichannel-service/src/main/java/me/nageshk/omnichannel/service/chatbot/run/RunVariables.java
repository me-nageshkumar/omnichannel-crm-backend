package me.nageshk.omnichannel.service.chatbot.run;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import me.nageshk.omnichannel.db.automation.EventData;
import me.nageshk.omnichannel.db.chatbot.StartedBy;
import me.nageshk.omnichannel.db.contact.Contact;
import me.nageshk.omnichannel.db.contact.ContactAttribute;
import me.nageshk.omnichannel.db.contact.ContactLabel;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import me.nageshk.omnichannel.db.phone.PhoneNumber;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * What a run knows (spec §4.4): {@code contact}, {@code chat}, {@code number}, {@code event} (the automation event's
 * facts, as the rule conditions saw them), {@code trigger}, {@code now}, then one branch per node that has run. The
 * nested maps are the Mustache context; {@link #flatten} turns them into dotted paths for the condition evaluator.
 */
@Component
public class RunVariables {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};
    private final ObjectMapper mapper;

    public RunVariables(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public Map<String, Object> initial(Contact contact, List<ContactAttribute> attributes, List<ContactLabel> labels,
            WaWebChat chat, PhoneNumber number, EventData event, StartedBy trigger, Instant now) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("contact", contactBranch(contact, attributes, labels));
        Map<String, Object> chatBranch = new LinkedHashMap<>();
        if (chat != null) {
            chatBranch.put("id", chat.getId());
            chatBranch.put("status", chat.getStatus() == null ? null : chat.getStatus().name());
            chatBranch.put("assignedUser", chat.getAssignedUserId());
            chatBranch.put("assignedTeam", chat.getAssignedTeamId());
        }
        v.put("chat", chatBranch);
        Map<String, Object> numberBranch = new LinkedHashMap<>();
        if (number != null) {
            numberBranch.put("label", number.getLabel());
            numberBranch.put("phone", number.getNumber());
        }
        v.put("number", numberBranch);
        v.put("event", event == null ? new LinkedHashMap<>() : eventBranch(event));
        Map<String, Object> t = new LinkedHashMap<>();
        if (trigger != null) {
            t.put("ruleId", trigger.ruleId());
            t.put("ruleName", trigger.ruleName());
            t.put("eventId", trigger.eventId());
        }
        t.put("at", now.toString());
        v.put("trigger", t);
        v.put("now", now.toString());
        return v;
    }

    /** Replaces the {@code contact} branch after an Update contact node. */
    public void refreshContact(Map<String, Object> vars, Contact contact, List<ContactAttribute> attributes,
            List<ContactLabel> labels) {
        vars.put("contact", contactBranch(contact, attributes, labels));
    }

    private static Map<String, Object> contactBranch(Contact c, List<ContactAttribute> attributes, List<ContactLabel> labels) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (c == null) return out;
        out.put("id", c.getId());
        out.put("name", c.getName());
        out.put("phone", c.getPhone());
        Map<String, Object> attrs = new LinkedHashMap<>();
        if (attributes != null && c.getAttributes() != null) {
            for (ContactAttribute a : attributes) {
                String value = c.getAttributes().get(a.getId());
                if (value != null) attrs.put(a.getKey(), value);
            }
        }
        out.put("attributes", attrs);
        List<String> names = new ArrayList<>();
        if (labels != null && c.getLabels() != null) {
            for (ContactLabel l : labels) if (c.getLabels().contains(l.getId())) names.add(l.getName());
        }
        out.put("labels", names);
        out.put("doNotContact", c.getDoNotContact() != null);
        return out;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> eventBranch(EventData event) {
        Map<String, Object> m = mapper.convertValue(event, MAP);
        Object chat = m.get("chat");
        if (chat instanceof Map<?, ?> c && c.containsKey("isNew")) {
            Map<String, Object> copy = new LinkedHashMap<>((Map<String, Object>) c);
            copy.put("new", copy.remove("isNew"));
            m.put("chat", copy);
        }
        m.values().removeIf(x -> x == null);
        return m;
    }

    /** Dotted paths for the condition evaluator; lists stay lists. */
    public static Map<String, Object> flatten(Map<String, Object> vars) {
        Map<String, Object> out = new LinkedHashMap<>();
        flattenInto("", vars, out);
        return out;
    }

    private static void flattenInto(String prefix, Map<?, ?> m, Map<String, Object> out) {
        for (Map.Entry<?, ?> e : m.entrySet()) {
            String key = prefix.isEmpty() ? String.valueOf(e.getKey()) : prefix + "." + e.getKey();
            if (e.getValue() instanceof Map<?, ?> child) flattenInto(key, child, out);
            else out.put(key, e.getValue());
        }
    }
}
