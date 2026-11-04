package me.nageshk.omnichannel.app.contact;

import me.nageshk.omnichannel.service.contact.ContactIdResolver;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * "Resolve hidden numbers": looks up the other WhatsApp id of every contact of the brand that lacks a phone or a
 * hidden id and chats on a ready number, and finishes merges left half done. The work runs in the background.
 */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}/contacts")
public class ContactResolveController {

    public record Queued(int queued) {}

    private final ContactIdResolver resolver;

    public ContactResolveController(ContactIdResolver resolver) {
        this.resolver = resolver;
    }

    @PostMapping("/resolve-ids")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("@access.has(#workspaceId, 'contact:edit')")
    public Queued resolveIds(@PathVariable String workspaceId) {
        return new Queued(resolver.queueBrand(workspaceId));
    }
}
