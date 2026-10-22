package me.nageshk.omnichannel.contact;

import java.util.List;
import me.nageshk.omnichannel.auth.AuthenticatedUser;
import me.nageshk.omnichannel.contact.advanced.RuleGroup;
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

/** A brand's saved contact filters: {@code contact:view} to list, {@code contact:bulk} to change. */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}/contact-filters")
public class ContactFilterController {

    public record FilterRequest(String name, ContactFilterSpec filter, RuleGroup rules) {}

    private final ContactFilterService service;

    public ContactFilterController(ContactFilterService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("@access.has(#workspaceId, 'contact:view')")
    public List<FilterView> list(@PathVariable String workspaceId) {
        return service.list(workspaceId);
    }

    @GetMapping("/{id}")
    @PreAuthorize("@access.has(#workspaceId, 'contact:view')")
    public FilterView get(@PathVariable String workspaceId, @PathVariable String id) {
        return service.get(workspaceId, id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@access.has(#workspaceId, 'contact:bulk')")
    public FilterView create(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String workspaceId,
            @RequestBody FilterRequest req) {
        return service.create(workspaceId, me.getUsername(), req.name(), req.filter(),
                req.rules());
    }

    @PutMapping("/{id}")
    @PreAuthorize("@access.has(#workspaceId, 'contact:bulk')")
    public FilterView update(@PathVariable String workspaceId, @PathVariable String id, @RequestBody FilterRequest req) {
        return service.update(workspaceId, id, req.name(), req.filter(), req.rules());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@access.has(#workspaceId, 'contact:bulk')")
    public void delete(@PathVariable String workspaceId, @PathVariable String id) {
        service.delete(workspaceId, id);
    }
}
