package me.nageshk.omnichannel.template;

import java.time.Instant;
import java.util.List;

/** API view of a message template. */
public record TemplateView(String id, String workspaceId, String channel, String name, String category,
        TemplateType type, TemplateContent content, MediaView media, List<PlaceholderView> placeholders,
        TemplateStatus status, Instant lastUsedAt, Person createdBy, Instant creationTime, Person updatedBy,
        Instant lastUpdateTime, Long version, List<String> warnings) {

    public record Person(String username, String name) {}

    public record MediaView(String mediaId, String fileName, String mimeType, long size) {}

    public record PlaceholderView(String name, PlaceholderKind kind, String attributeKey, String attributeId,
            String attributeName, boolean missing, String label, String example, boolean required, String fallback) {}

    public static TemplateView of(MessageTemplate t) {
        TemplateMediaRef m = t.getMedia();
        return new TemplateView(t.getId(), t.getWorkspaceId(), t.getChannel().name(), t.getName(), t.getCategory(),
                t.getType(), t.getContent(),
                m == null ? null : new MediaView(m.getMediaId(), m.getFileName(), m.getMimeType(), m.getSize()),
                t.getPlaceholders().stream().map(p -> new PlaceholderView(p.getName(), p.getKind(),
                        p.getAttributeKey(), p.getAttributeId(), null, false, p.getLabel(), p.getExample(),
                        p.isRequired(), p.getFallback())).toList(),
                t.getStatus(), t.getLastUsedAt(), new Person(t.getCreatedByUsername(), t.getCreatedByName()),
                t.getCreationTime(), new Person(t.getUpdatedByUsername(), t.getUpdatedByName()),
                t.getLastUpdateTime(), t.getVersion(), List.of());
    }
}
