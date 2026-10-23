package me.nageshk.omnichannel.app.access;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import me.nageshk.omnichannel.db.access.Role;
import me.nageshk.omnichannel.db.access.RoleChange;
import me.nageshk.omnichannel.db.access.RoleLevel;
import me.nageshk.omnichannel.security.access.RoleService.PreviewChange;
import me.nageshk.omnichannel.security.access.RoleService;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Super Admin console: role management. */
@RestController
@PreAuthorize("hasAuthority('SUPER_ADMIN')")
@RequestMapping("/api")
public class RoleController {

    public record RoleView(String id, String name, String description, RoleLevel level, List<String> authorities,
            boolean builtIn, long userCount) {}

    public record CreateRoleRequest(@NotNull String name, @Size(max = 500) String description,
            @NotNull RoleLevel level, @NotNull Set<String> authorities) {}

    public record UpdateRoleRequest(String name, @Size(max = 500) String description,
            @NotNull Set<String> authorities) {}

    public record PreviewRequest(@NotNull Set<String> authorities) {}

    public record PreviewResponse(List<PreviewChange> changes) {}

    public record ChangeView(String roleId, String roleName, String changedBy, Instant at, RoleChange.Action action,
            List<String> added, List<String> removed, String descriptionBefore, String descriptionAfter) {}

    private final RoleService service;

    public RoleController(RoleService service) {
        this.service = service;
    }

    @GetMapping("/roles")
    public List<RoleView> list() {
        return service.list().stream().map(this::view).toList();
    }

    @PostMapping("/roles")
    @ResponseStatus(HttpStatus.CREATED)
    public RoleView create(@Valid @RequestBody CreateRoleRequest req, Authentication auth) {
        return view(service.create(req.name(), req.description(), req.level(), req.authorities(), auth.getName()));
    }

    @PutMapping("/roles/{id}")
    public RoleView update(@PathVariable String id, @Valid @RequestBody UpdateRoleRequest req, Authentication auth) {
        return view(service.update(id, req.name(), req.description(), req.authorities(), auth.getName()));
    }

    @DeleteMapping("/roles/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id, Authentication auth) {
        service.delete(id, auth.getName());
    }

    @PostMapping("/roles/{id}/preview")
    public PreviewResponse preview(@PathVariable String id, @Valid @RequestBody PreviewRequest req) {
        return new PreviewResponse(service.preview(id, req.authorities()));
    }

    @GetMapping("/roles/{id}/changes")
    public List<ChangeView> changes(@PathVariable String id) {
        return service.changesFor(id).stream().map(c -> new ChangeView(c.getRoleId(), c.getRoleName(),
                c.getChangedBy(), c.getAt(), c.getAction(), c.getAdded(), c.getRemoved(),
                c.getDescriptionBefore(), c.getDescriptionAfter())).toList();
    }

    private RoleView view(Role r) {
        return new RoleView(r.getId(), r.getName(), r.getDescription(), r.getLevel(),
                r.getAuthorities().stream().sorted().toList(), r.isBuiltIn(), service.userCount(r));
    }
}
