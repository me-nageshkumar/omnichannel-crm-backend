package me.nageshk.omnichannel.meta.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/** An imported Meta template as the console and the senders see it (spec 2026-11-20 §5.6). */
public record CloudTemplateInfo(String id, String connectionId, String wabaId, String name, String language,
        String category, String status, String rejectedReason, String parameterFormat, JsonNode components,
        Header header, List<Variable> variables, Map<String, Fill> fills, boolean supported, String unsupportedReason,
        boolean sendable, Instant syncedAt, Instant lastUsedAt) {

    /** {@code format} NONE | TEXT | IMAGE | VIDEO | DOCUMENT (anything else makes the template unsupported). */
    public record Header(String format) {
        public boolean media() {
            return "IMAGE".equals(format) || "VIDEO".equals(format) || "DOCUMENT".equals(format);
        }
    }

    public record Variable(String key, String component, int index, String example) {}

    /** Where a blank's value comes from by default: a contact field, a contact attribute, or the sender. */
    public record Fill(FillKind kind, String attributeKey, String fallback) {}

    public enum FillKind { CONTACT_FIELD, CONTACT_ATTRIBUTE, AGENT }
}
