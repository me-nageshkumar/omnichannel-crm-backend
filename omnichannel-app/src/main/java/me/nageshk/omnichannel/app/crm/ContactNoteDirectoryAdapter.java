package me.nageshk.omnichannel.app.crm;

import java.util.List;
import me.nageshk.omnichannel.crm.platform.ContactNoteDirectory;
import me.nageshk.omnichannel.crm.platform.PlatformNote;
import me.nageshk.omnichannel.db.contact.ContactNoteRepository;
import org.springframework.stereotype.Component;

@Component
class ContactNoteDirectoryAdapter implements ContactNoteDirectory {

    private final ContactNoteRepository notes;

    ContactNoteDirectoryAdapter(ContactNoteRepository notes) {
        this.notes = notes;
    }

    @Override
    public List<PlatformNote> notes(String workspaceId, String contactId) {
        return notes.findByWorkspaceIdAndContactIdOrderByCreationTimeDesc(workspaceId, contactId).stream()
                .map(n -> new PlatformNote(n.getId(), n.getText(), n.getCreatedByName(), n.getCreationTime()))
                .toList();
    }
}
