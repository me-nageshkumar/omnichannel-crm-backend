package me.nageshk.omnichannel.contact;

import java.util.List;
import me.nageshk.omnichannel.access.AccessService;
import me.nageshk.omnichannel.api.ForbiddenException;
import me.nageshk.omnichannel.api.NotFoundException;
import me.nageshk.omnichannel.api.ValidationException;
import me.nageshk.omnichannel.auth.AuthenticatedUser;
import me.nageshk.omnichannel.user.UserRepository;
import org.springframework.stereotype.Service;

/** Notes on a brand's contacts: anyone who may annotate adds; the author edits; the author or a deleter removes. */
@Service
public class ContactNoteService {

    public static final int MAX_TEXT = 2000;

    private final ContactNoteRepository notes;
    private final ContactService contacts;
    private final UserRepository users;
    private final AccessService access;

    public ContactNoteService(ContactNoteRepository notes, ContactService contacts, UserRepository users,
            AccessService access) {
        this.notes = notes;
        this.contacts = contacts;
        this.users = users;
        this.access = access;
    }

    /** Newest first. */
    public List<NoteView> list(AuthenticatedUser me, String workspaceId, String contactId) {
        contacts.require(workspaceId, contactId);
        return notes.findByWorkspaceIdAndContactIdOrderByCreationTimeDesc(workspaceId, contactId).stream()
                .map(n -> NoteView.of(n, me.getUsername())).toList();
    }

    public NoteView add(AuthenticatedUser me, String workspaceId, String contactId, String text) {
        contacts.require(workspaceId, contactId);
        ContactNote n = new ContactNote();
        n.setWorkspaceId(workspaceId);
        n.setContactId(contactId);
        n.setText(clean(text));
        n.setCreatedByUsername(me.getUsername());
        n.setCreatedByName(users.findById(me.userId()).map(u -> u.getName()).orElse(me.getUsername()));
        return NoteView.of(notes.save(n), me.getUsername());
    }

    /** Only the author edits. */
    public NoteView edit(AuthenticatedUser me, String workspaceId, String contactId, String noteId, String text) {
        ContactNote n = require(workspaceId, contactId, noteId);
        if (!me.getUsername().equals(n.getCreatedByUsername())) throw new ForbiddenException("only the author can edit a note");
        n.setText(clean(text));
        return NoteView.of(notes.save(n), me.getUsername());
    }

    /** The author, or anyone with {@code contact:delete} in the brand. */
    public void delete(AuthenticatedUser me, String workspaceId, String contactId, String noteId) {
        ContactNote n = require(workspaceId, contactId, noteId);
        boolean author = me.getUsername().equals(n.getCreatedByUsername());
        if (!author && !access.has(workspaceId, "contact:delete")) {
            throw new ForbiddenException("only the author or a manager can delete a note");
        }
        notes.delete(n);
    }

    private ContactNote require(String workspaceId, String contactId, String noteId) {
        contacts.require(workspaceId, contactId);
        return notes.findByIdAndWorkspaceIdAndContactId(noteId, workspaceId, contactId)
                .orElseThrow(() -> new NotFoundException("note not found"));
    }

    private static String clean(String text) {
        String t = text == null ? "" : text.trim();
        if (t.isEmpty() || t.length() > MAX_TEXT) {
            throw new ValidationException("text", "must be 1 to " + MAX_TEXT + " characters");
        }
        return t;
    }
}
