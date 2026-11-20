package me.nageshk.omnichannel.meta.connection;

import java.util.List;
import me.nageshk.omnichannel.meta.connection.ConnectionView.Check;
import me.nageshk.omnichannel.meta.connection.ConnectionView.Save;
import me.nageshk.omnichannel.security.auth.AuthenticatedUser;
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

/** A brand's Meta connections (spec 2026-11-20 §5.7). Phone-number authorities: no new ones. */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}/meta/connections")
class MetaConnectionController {

    private final MetaConnectionService service;

    MetaConnectionController(MetaConnectionService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("@access.has(#workspaceId, 'phone-number:view')")
    List<ConnectionView> list(@PathVariable String workspaceId) {
        return service.list(workspaceId);
    }

    @GetMapping("/{id}")
    @PreAuthorize("@access.has(#workspaceId, 'phone-number:view')")
    ConnectionView get(@PathVariable String workspaceId, @PathVariable String id) {
        return service.get(workspaceId, id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@access.has(#workspaceId, 'phone-number:manage')")
    ConnectionView create(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String workspaceId,
            @RequestBody Save body) {
        return service.create(workspaceId, me.customerId(), me.userId(), body);
    }

    @PutMapping("/{id}")
    @PreAuthorize("@access.has(#workspaceId, 'phone-number:manage')")
    ConnectionView replace(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String workspaceId,
            @PathVariable String id, @RequestBody Save body) {
        return service.replace(workspaceId, id, me.userId(), body);
    }

    @PostMapping("/{id}/check")
    @PreAuthorize("@access.has(#workspaceId, 'phone-number:manage')")
    Check check(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String workspaceId,
            @PathVariable String id) {
        return service.check(workspaceId, id, me.userId());
    }

    @PostMapping("/{id}/webhook")
    @PreAuthorize("@access.has(#workspaceId, 'phone-number:manage')")
    ConnectionView retryWebhook(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String workspaceId,
            @PathVariable String id) {
        return service.retryWebhook(workspaceId, id, me.userId());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@access.has(#workspaceId, 'phone-number:manage')")
    void delete(@PathVariable String workspaceId, @PathVariable String id) {
        service.delete(workspaceId, id);
    }
}
