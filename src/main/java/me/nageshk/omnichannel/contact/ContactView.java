package me.nageshk.omnichannel.contact;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** API view of a contact: label ids and attribute values keyed by attribute id. */
public record ContactView(String id, String name, String phone, String waId, boolean numberHidden, List<String> labels,
        Map<String, String> attributes, ContactSource source, Instant lastMessageAt, Instant creationTime,
        Instant lastUpdateTime, DoNotContact doNotContact) {

    public static ContactView of(Contact c) {
        return new ContactView(c.getId(), c.getName(), c.getPhone(), c.getWaId(), c.getPhone() == null,
                c.getLabels().stream().sorted().toList(), Map.copyOf(c.getAttributes()), c.getSource(),
                c.getLastMessageAt(), c.getCreationTime(), c.getLastUpdateTime(), c.getDoNotContact());
    }
}
