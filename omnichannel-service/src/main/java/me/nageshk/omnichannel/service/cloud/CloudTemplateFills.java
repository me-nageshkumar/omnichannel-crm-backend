package me.nageshk.omnichannel.service.cloud;

import java.util.LinkedHashMap;
import java.util.Map;
import me.nageshk.omnichannel.db.contact.Contact;
import me.nageshk.omnichannel.db.contact.ContactAttributeRepository;
import me.nageshk.omnichannel.db.contact.ContactRepository;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import me.nageshk.omnichannel.meta.api.CloudTemplateInfo;
import org.springframework.stereotype.Component;

/**
 * A Meta template's values for a chat (spec 2026-11-20 §5.6): each blank's fill (the contact's name or phone, a
 * contact attribute, or nothing for the sender to type), its fallback when that is empty, and any value given winning.
 */
@Component
public class CloudTemplateFills {

    private final ContactRepository contacts;
    private final ContactAttributeRepository attributes;

    public CloudTemplateFills(ContactRepository contacts, ContactAttributeRepository attributes) {
        this.contacts = contacts;
        this.attributes = attributes;
    }

    public Map<String, String> resolve(CloudTemplateInfo t, WaWebChat chat, Map<String, String> given) {
        Contact contact = chat == null || chat.getContactId() == null ? null
                : contacts.findById(chat.getContactId()).orElse(null);
        Map<String, String> out = new LinkedHashMap<>();
        for (CloudTemplateInfo.Variable v : t.variables()) {
            String value = given == null ? null : given.get(v.key());
            if (blank(value)) {
                CloudTemplateInfo.Fill f = t.fills().get(v.key());
                value = f == null ? null : fill(f, contact, chat == null ? null : chat.getWorkspaceId());
                if (blank(value) && f != null) value = f.fallback();
            }
            if (!blank(value)) out.put(v.key(), value.strip());
        }
        return out;
    }

    private String fill(CloudTemplateInfo.Fill f, Contact contact, String workspaceId) {
        if (contact == null) return null;
        return switch (f.kind()) {
            case CONTACT_FIELD -> "phone".equals(f.attributeKey())
                    ? (blank(contact.getPhone()) ? null : "+" + contact.getPhone().strip())
                    : contact.getName();
            case CONTACT_ATTRIBUTE -> attributes.findByWorkspaceIdAndKey(workspaceId, f.attributeKey())
                    .map(a -> contact.getAttributes() == null ? null : contact.getAttributes().get(a.getId()))
                    .map(Object::toString).orElse(null);
            case AGENT -> null;
        };
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
