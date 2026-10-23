package me.nageshk.omnichannel.service.contact;

import java.time.Instant;
import me.nageshk.omnichannel.db.contact.ContactNote;

/** API view of a contact note; {@code mine} is whether the caller wrote it. */
public record NoteView(String id, String contactId, String text, CreatedBy createdBy, Instant creationTime,
        Instant lastUpdateTime, boolean mine) {

    public record CreatedBy(String username, String name) {}

    public static NoteView of(ContactNote n, String viewerUsername) {
        return new NoteView(n.getId(), n.getContactId(), n.getText(),
                new CreatedBy(n.getCreatedByUsername(), n.getCreatedByName()), n.getCreationTime(),
                n.getLastUpdateTime(), n.getCreatedByUsername() != null && n.getCreatedByUsername().equals(viewerUsername));
    }
}
