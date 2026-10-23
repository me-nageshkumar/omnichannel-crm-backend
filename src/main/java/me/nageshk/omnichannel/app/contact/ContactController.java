package me.nageshk.omnichannel.app.contact;

import java.util.Map;
import java.util.Set;
import me.nageshk.omnichannel.db.contact.ContactFilterSpec;
import me.nageshk.omnichannel.db.contact.DoNotContact;
import me.nageshk.omnichannel.security.auth.AuthenticatedUser;
import me.nageshk.omnichannel.service.contact.ContactSearchService;
import me.nageshk.omnichannel.service.contact.ContactService;
import me.nageshk.omnichannel.service.contact.ContactSort;
import me.nageshk.omnichannel.service.contact.ContactView;
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

/** One brand's contacts: view, add by hand, edit, delete, and tag with labels. */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}/contacts")
public class ContactController {

    public record CreateRequest(String name, String phone, Set<String> labels, Map<String, String> attributes) {}

    public record UpdateRequest(String name, Map<String, String> attributes) {}

    public record SearchRequest(ContactFilterSpec filter, String filterId, ContactSort sort, Integer page) {}

    private final ContactService service;
    private final ContactSearchService search;

    public ContactController(ContactService service, ContactSearchService search) {
        this.service = service;
        this.search = search;
    }

    @PostMapping("/search")
    @PreAuthorize("@access.has(#workspaceId, 'contact:view')")
    public ContactSearchService.Page search(@PathVariable String workspaceId, @RequestBody SearchRequest req) {
        return search.search(workspaceId, req.filter(), req.filterId(), req.sort(), req.page());
    }

    @GetMapping("/{id}")
    @PreAuthorize("@access.has(#workspaceId, 'contact:view')")
    public ContactView get(@PathVariable String workspaceId, @PathVariable String id) {
        return ContactView.of(service.require(workspaceId, id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@access.has(#workspaceId, 'contact:edit')")
    public ContactView create(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String workspaceId,
            @RequestBody CreateRequest req) {
        return ContactView.of(service.create(workspaceId, me.customerId(), req.name(), req.phone(), req.labels(),
                req.attributes()));
    }

    @PutMapping("/{id}")
    @PreAuthorize("@access.has(#workspaceId, 'contact:edit')")
    public ContactView update(@PathVariable String workspaceId, @PathVariable String id, @RequestBody UpdateRequest req) {
        return ContactView.of(service.update(workspaceId, id, req.name(), req.attributes()));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@access.has(#workspaceId, 'contact:delete')")
    public void delete(@PathVariable String workspaceId, @PathVariable String id) {
        service.delete(workspaceId, id);
    }

    @PutMapping("/{id}/do-not-contact")
    @PreAuthorize("@access.has(#workspaceId, 'contact:edit')")
    public ContactView markDoNotContact(@PathVariable String workspaceId, @PathVariable String id) {
        return ContactView.of(service.markDoNotContact(workspaceId, id, DoNotContact.Reason.MANUAL, null));
    }

    @DeleteMapping("/{id}/do-not-contact")
    @PreAuthorize("@access.has(#workspaceId, 'contact:edit')")
    public ContactView clearDoNotContact(@PathVariable String workspaceId, @PathVariable String id) {
        return ContactView.of(service.clearDoNotContact(workspaceId, id));
    }

    @PostMapping("/{id}/labels/{labelId}")
    @PreAuthorize("@access.has(#workspaceId, 'contact:annotate')")
    public ContactView addLabel(@PathVariable String workspaceId, @PathVariable String id, @PathVariable String labelId) {
        return ContactView.of(service.addLabel(workspaceId, id, labelId));
    }

    @DeleteMapping("/{id}/labels/{labelId}")
    @PreAuthorize("@access.has(#workspaceId, 'contact:annotate')")
    public ContactView removeLabel(@PathVariable String workspaceId, @PathVariable String id,
            @PathVariable String labelId) {
        return ContactView.of(service.removeLabel(workspaceId, id, labelId));
    }
}
