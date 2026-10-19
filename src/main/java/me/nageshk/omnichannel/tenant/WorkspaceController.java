package me.nageshk.omnichannel.tenant;

import java.util.List;
import me.nageshk.omnichannel.access.AccessService;
import me.nageshk.omnichannel.auth.AuthenticatedUser;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The caller's own workspaces: list those it can act in, create one in its customer
 * ({@code workspace:create}), rename one ({@code workspace:settings} there). A workspace the caller
 * cannot reach answers 404.
 */
@RestController
@RequestMapping("/api/workspaces")
public class WorkspaceController {

    public record WorkspaceView(String id, String name) {}

    public record NameRequest(String name) {}

    private final WorkspaceService service;
    private final AccessService access;

    public WorkspaceController(WorkspaceService service, AccessService access) {
        this.service = service;
        this.access = access;
    }

    @GetMapping
    public List<WorkspaceView> mine(@AuthenticationPrincipal AuthenticatedUser me) {
        return access.workspacesOf(me).stream().map(WorkspaceController::view).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('workspace:create')")
    public WorkspaceView create(@AuthenticationPrincipal AuthenticatedUser me, @RequestBody NameRequest req) {
        return view(service.create(me.customerId(), req.name()));
    }

    @PutMapping("/{workspaceId}")
    @PreAuthorize("@access.has(#workspaceId, 'workspace:settings')")
    public WorkspaceView rename(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String workspaceId,
            @RequestBody NameRequest req) {
        return view(service.rename(me.customerId(), workspaceId, req.name()));
    }

    private static WorkspaceView view(Workspace w) {
        return new WorkspaceView(w.getId(), w.getName());
    }
}
