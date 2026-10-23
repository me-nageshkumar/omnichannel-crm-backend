package me.nageshk.omnichannel.app.contact;

import me.nageshk.omnichannel.service.contact.ContactBulkService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Counting and bulk actions on a selection of a brand's contacts; delete also needs {@code contact:delete}. */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}/contacts")
public class ContactBulkController {

    public record CountRequest(ContactBulkService.Selection selection) {}

    public record CountResponse(long count) {}

    private final ContactBulkService service;

    public ContactBulkController(ContactBulkService service) {
        this.service = service;
    }

    @PostMapping("/count")
    @PreAuthorize("@access.has(#workspaceId, 'contact:view')")
    public CountResponse count(@PathVariable String workspaceId, @RequestBody CountRequest req) {
        return new CountResponse(service.count(workspaceId, req.selection()));
    }

    @PostMapping("/bulk")
    @PreAuthorize("@access.has(#workspaceId, 'contact:bulk')")
    public ContactBulkService.Result bulk(@PathVariable String workspaceId, @RequestBody ContactBulkService.Request req) {
        return service.run(workspaceId, req);
    }
}
