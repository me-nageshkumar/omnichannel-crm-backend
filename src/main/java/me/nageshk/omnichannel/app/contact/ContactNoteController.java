package me.nageshk.omnichannel.app.contact;

import java.util.List;
import me.nageshk.omnichannel.security.auth.AuthenticatedUser;
import me.nageshk.omnichannel.service.contact.ContactNoteService;
import me.nageshk.omnichannel.service.contact.NoteView;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Notes on one contact: {@code contact:view} to read, {@code contact:annotate} to write. */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}/contacts/{contactId}/notes")
public class ContactNoteController {

    public record NoteRequest(String text) {}

    private final ContactNoteService service;

    public ContactNoteController(ContactNoteService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("@access.has(#workspaceId, 'contact:view')")
    public List<NoteView> list(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String workspaceId,
            @PathVariable String contactId) {
        return service.list(me, workspaceId, contactId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@access.has(#workspaceId, 'contact:annotate')")
    public NoteView add(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String workspaceId,
            @PathVariable String contactId, @RequestBody NoteRequest req) {
        return service.add(me, workspaceId, contactId, req.text());
    }

    @PutMapping("/{noteId}")
    @PreAuthorize("@access.has(#workspaceId, 'contact:annotate')")
    public NoteView edit(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String workspaceId,
            @PathVariable String contactId, @PathVariable String noteId, @RequestBody NoteRequest req) {
        return service.edit(me, workspaceId, contactId, noteId, req.text());
    }

    /** The author (with annotate) or anyone with delete; the service decides which. */
    @DeleteMapping("/{noteId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@access.has(#workspaceId, 'contact:annotate') or @access.has(#workspaceId, 'contact:delete')")
    public void delete(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String workspaceId,
            @PathVariable String contactId, @PathVariable String noteId) {
        service.delete(me, workspaceId, contactId, noteId);
    }
}
