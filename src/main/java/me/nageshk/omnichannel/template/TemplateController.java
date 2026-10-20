package me.nageshk.omnichannel.template;

import java.util.List;
import me.nageshk.omnichannel.auth.AuthenticatedUser;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** A brand's message templates: {@code template:view} to read, {@code template:manage} to change. */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}/templates")
public class TemplateController {

    private final TemplateService service;
    private final TemplateRenderer renderer;

    public TemplateController(TemplateService service, TemplateRenderer renderer) {
        this.service = service;
        this.renderer = renderer;
    }

    @GetMapping
    @PreAuthorize("@access.has(#workspaceId, 'template:view')")
    public List<TemplateView> list(@PathVariable String workspaceId, @RequestParam(required = false) String q,
            @RequestParam(required = false) TemplateType type, @RequestParam(required = false) String category,
            @RequestParam(required = false) TemplateStatus status) {
        return service.list(workspaceId, q, type, category, status);
    }

    @GetMapping("/categories")
    @PreAuthorize("@access.has(#workspaceId, 'template:view')")
    public List<String> categories(@PathVariable String workspaceId) {
        return service.categories(workspaceId);
    }

    @GetMapping("/{id}")
    @PreAuthorize("@access.has(#workspaceId, 'template:view')")
    public TemplateView get(@PathVariable String workspaceId, @PathVariable String id) {
        return service.get(workspaceId, id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@access.has(#workspaceId, 'template:manage')")
    public TemplateView create(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String workspaceId,
            @RequestBody(required = false) TemplateRequest req) {
        return service.create(me, workspaceId, req);
    }

    @PutMapping("/{id}")
    @PreAuthorize("@access.has(#workspaceId, 'template:manage')")
    public TemplateView update(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String workspaceId,
            @PathVariable String id, @RequestBody(required = false) TemplateRequest req) {
        return service.update(me, workspaceId, id, req);
    }

    @PostMapping("/{id}/archive")
    @PreAuthorize("@access.has(#workspaceId, 'template:manage')")
    public TemplateView archive(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String workspaceId,
            @PathVariable String id) {
        return service.archive(me, workspaceId, id);
    }

    @PostMapping("/{id}/unarchive")
    @PreAuthorize("@access.has(#workspaceId, 'template:manage')")
    public TemplateView unarchive(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String workspaceId,
            @PathVariable String id) {
        return service.unarchive(me, workspaceId, id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@access.has(#workspaceId, 'template:manage')")
    public void delete(@PathVariable String workspaceId, @PathVariable String id) {
        service.delete(workspaceId, id);
    }

    /** Copies the template into another brand of the same customer; the caller needs manage there too. */
    @PostMapping("/{id}/duplicate")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@access.has(#workspaceId, 'template:manage')")
    public TemplateView duplicate(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String workspaceId,
            @PathVariable String id, @RequestBody(required = false) DuplicateRequest req) {
        return service.duplicate(me, workspaceId, id, req == null ? null : req.targetWorkspaceId(),
                req == null ? null : req.name());
    }

    /** Fills the template's placeholders; with a {@code contactId} the caller also needs {@code contact:view}. */
    @PostMapping("/{id}/render")
    @PreAuthorize("@access.has(#workspaceId, 'template:view')")
    public RenderedTemplate render(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String workspaceId,
            @PathVariable String id, @RequestBody(required = false) RenderRequest req) {
        return renderer.render(me, workspaceId, id, req);
    }
}
