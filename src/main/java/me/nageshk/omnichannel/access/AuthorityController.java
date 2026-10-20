package me.nageshk.omnichannel.access;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import me.nageshk.omnichannel.access.AuthorityCheckScanner.CheckKind;
import me.nageshk.omnichannel.access.AuthorityCheckScanner.FoundCheck;
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

/** Super Admin console: the authority catalogue, what code checks but nobody created, and its history. */
@RestController
@PreAuthorize("hasAuthority('SUPER_ADMIN')")
@RequestMapping("/api/authorities")
public class AuthorityController {

    public record AuthorityView(String code, String group, RoleLevel level, String description, boolean builtIn,
            AuthorityStatus status, List<String> checkedIn, List<String> roles, Instant updatedAt, String updatedBy) {}

    public record NeedsCreatingView(String code, RoleLevel suggestedLevel, String suggestedGroup,
            List<String> checkedIn) {}

    public record ChangeView(String code, AuthorityChange.Action action, String groupBefore, String groupAfter,
            RoleLevel levelBefore, RoleLevel levelAfter, String descriptionBefore, String descriptionAfter,
            String changedBy, Instant at) {}

    public record CreateRequest(String code, String group, RoleLevel level, String description) {}

    public record UpdateRequest(String group, RoleLevel level, String description) {}

    private final AuthorityService service;

    public AuthorityController(AuthorityService service) {
        this.service = service;
    }

    @GetMapping
    public List<AuthorityView> list() {
        return service.list().stream().map(this::view).toList();
    }

    @GetMapping("/needs-creating")
    public List<NeedsCreatingView> needsCreating() {
        return service.needsCreating().stream().map(f -> new NeedsCreatingView(f.code(),
                f.kinds().equals(EnumSet.of(CheckKind.BRAND)) ? RoleLevel.BRAND : null,
                f.code().substring(0, f.code().indexOf(':')), f.locations())).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AuthorityView create(@RequestBody CreateRequest req, Authentication auth) {
        return view(service.create(req.code(), req.group(), req.level(), req.description(), auth.getName()));
    }

    @PutMapping("/{code}")
    public AuthorityView update(@PathVariable String code, @RequestBody UpdateRequest req, Authentication auth) {
        return view(service.update(code, req.group(), req.level(), req.description(), auth.getName()));
    }

    @DeleteMapping("/{code}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String code, Authentication auth) {
        service.delete(code, auth.getName());
    }

    @GetMapping("/{code}/history")
    public List<ChangeView> history(@PathVariable String code) {
        return service.history(code).stream().map(c -> new ChangeView(c.getCode(), c.getAction(), c.getGroupBefore(),
                c.getGroupAfter(), c.getLevelBefore(), c.getLevelAfter(), c.getDescriptionBefore(),
                c.getDescriptionAfter(), c.getChangedBy(), c.getAt())).toList();
    }

    private AuthorityView view(AuthorityDefinition d) {
        FoundCheck found = service.checkOf(d.getCode());
        return new AuthorityView(d.getCode(), d.getGroup(), d.getLevel(), d.getDescription(), d.isBuiltIn(),
                AuthorityStatus.statusOf(d, found), found == null ? List.of() : found.locations(),
                service.rolesHolding(d.getCode()), d.getUpdatedAt(), d.getUpdatedBy());
    }
}
