package me.nageshk.omnichannel.service.template;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import me.nageshk.omnichannel.db.contact.ContactAttribute;
import me.nageshk.omnichannel.db.template.MessageTemplate;
import me.nageshk.omnichannel.db.template.PlaceholderKind;
import me.nageshk.omnichannel.db.template.TemplateContent;
import me.nageshk.omnichannel.db.template.TemplateMediaRef;
import me.nageshk.omnichannel.db.template.TemplatePlaceholder;
import me.nageshk.omnichannel.db.template.TemplateStatus;
import me.nageshk.omnichannel.db.template.TemplateType;

/** API view of a message template. */
public record TemplateView(String id, String workspaceId, String channel, String name, String category,
        TemplateType type, TemplateContent content, MediaView media, List<PlaceholderView> placeholders,
        TemplateStatus status, Instant lastUsedAt, Person createdBy, Instant creationTime, Person updatedBy,
        Instant lastUpdateTime, Long version, List<String> warnings) {

    public record Person(String username, String name) {}

    public record PlaceholderView(String name, PlaceholderKind kind, String attributeKey, String attributeId,
            String attributeName, boolean missing, String label, String example, boolean required, String fallback) {}

    /**
     * The view of a template; {@code attributes} are the brand's current contact attributes, used to resolve each
     * attribute placeholder's name, whether it is missing, and the warnings.
     */
    public static TemplateView of(MessageTemplate t, List<ContactAttribute> attributes) {
        Map<String, ContactAttribute> byId = new HashMap<>();
        Map<String, ContactAttribute> byKey = new HashMap<>();
        for (ContactAttribute a : attributes) {
            byId.put(a.getId(), a);
            if (a.getKey() != null) byKey.put(a.getKey(), a);
        }
        List<String> warnings = new ArrayList<>();
        List<PlaceholderView> placeholders = new ArrayList<>();
        for (TemplatePlaceholder p : t.getPlaceholders()) {
            ContactAttribute bound = p.getKind() == PlaceholderKind.CONTACT_ATTRIBUTE && p.getAttributeId() != null
                    ? byId.get(p.getAttributeId()) : null;
            boolean missing = p.getKind() == PlaceholderKind.CONTACT_ATTRIBUTE && bound == null;
            if (missing) {
                String key = p.getAttributeKey();
                warnings.add(byKey.containsKey(key)
                        ? "Attribute \"" + key + "\" was deleted — a new \"" + key + "\" exists; save the template to use it"
                        : "Attribute \"" + key + "\" was deleted — fallback will be used");
            }
            placeholders.add(new PlaceholderView(p.getName(), p.getKind(), p.getAttributeKey(), p.getAttributeId(),
                    bound == null ? null : bound.getName(), missing, p.getLabel(), p.getExample(), p.isRequired(),
                    p.getFallback()));
        }
        TemplateMediaRef m = t.getMedia();
        return new TemplateView(t.getId(), t.getWorkspaceId(), t.getChannel().name(), t.getName(), t.getCategory(),
                t.getType(), t.getContent(),
                m == null ? null : new MediaView(m.getMediaId(), m.getFileName(), m.getMimeType(), m.getSize()),
                placeholders, t.getStatus(), t.getLastUsedAt(),
                new Person(t.getCreatedByUsername(), t.getCreatedByName()), t.getCreationTime(),
                new Person(t.getUpdatedByUsername(), t.getUpdatedByName()), t.getLastUpdateTime(), t.getVersion(),
                warnings);
    }
}
