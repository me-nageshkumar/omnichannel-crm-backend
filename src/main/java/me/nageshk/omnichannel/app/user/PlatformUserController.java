package me.nageshk.omnichannel.app.user;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import me.nageshk.omnichannel.common.api.ForbiddenException;
import me.nageshk.omnichannel.common.api.NotFoundException;
import me.nageshk.omnichannel.common.api.ValidationException;
import me.nageshk.omnichannel.common.enablement.EnableLevel;
import me.nageshk.omnichannel.db.access.Role;
import me.nageshk.omnichannel.db.access.RoleLevel;
import me.nageshk.omnichannel.db.user.User;
import me.nageshk.omnichannel.db.user.UserRepository;
import me.nageshk.omnichannel.security.access.AccessService;
import me.nageshk.omnichannel.security.access.AssignmentPolicy.Actor;
import me.nageshk.omnichannel.security.access.AssignmentPolicy;
import me.nageshk.omnichannel.security.access.BuiltInRoles;
import me.nageshk.omnichannel.security.access.RoleCache;
import me.nageshk.omnichannel.security.auth.AuthenticatedUser;
import me.nageshk.omnichannel.security.user.TemporaryPasswordResponse;
import me.nageshk.omnichannel.security.user.UserAdminService.NewLogin;
import me.nageshk.omnichannel.security.user.UserAdminService;
import me.nageshk.omnichannel.security.user.UserService.RoleRef;
import me.nageshk.omnichannel.security.user.UserService;
import me.nageshk.omnichannel.security.user.UserSummary;
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
 * Platform staff logins (users with no customer). Needs {@code platform-user:manage}; which roles may
 * be given and which logins may be managed follow {@link AssignmentPolicy}. The Super Admin's login
 * is not listed and cannot be managed here, and the {@code SUPER_ADMIN} role is never given.
 */
@RestController
@PreAuthorize("hasAuthority('platform-user:manage')")
@RequestMapping("/api/platform-users")
public class PlatformUserController {

    public record CreateRequest(@NotBlank String name, @NotBlank String username, String email, String mobileNumber,
            @NotBlank String roleId, String temporaryPassword) {}

    public record UpdateRequest(@NotBlank String name, String email, String mobileNumber, @NotBlank String roleId) {}

    public record ResetPasswordRequest(String temporaryPassword) {}

    private final UserRepository users;
    private final UserAdminService admin;
    private final AccessService access;
    private final AssignmentPolicy policy;
    private final RoleCache roles;

    public PlatformUserController(UserRepository users, UserAdminService admin, AccessService access,
            AssignmentPolicy policy, RoleCache roles) {
        this.users = users;
        this.admin = admin;
        this.access = access;
        this.policy = policy;
        this.roles = roles;
    }

    /** Every platform user except the Super Admin, by name. */
    @GetMapping
    public List<UserSummary> list() {
        return admin.summaries(users.findByCustomerIdIsNullOrderByName().stream()
                .filter(u -> !isSuperAdmin(u))
                .toList());
    }

    @GetMapping("/assignable-roles")
    public List<RoleRef> assignableRoles(@AuthenticationPrincipal AuthenticatedUser me) {
        return policy.assignablePlatformRoles(access.actorOf(me), roles.all()).stream()
                .sorted(Comparator.comparing(Role::getName))
                .map(r -> new RoleRef(r.getId(), r.getName()))
                .toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TemporaryPasswordResponse create(@AuthenticationPrincipal AuthenticatedUser me,
            @Valid @RequestBody CreateRequest req) {
        Role role = givable(access.actorOf(me), req.roleId());
        User created = admin.create(
                new NewLogin(req.name(), req.username(), req.email(), req.mobileNumber(), req.temporaryPassword()),
                null, role.getId(), List.of());
        return TemporaryPasswordResponse.created(admin.summary(created), req.temporaryPassword());
    }

    @PutMapping("/{id}")
    public UserSummary update(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String id,
            @Valid @RequestBody UpdateRequest req) {
        Actor actor = access.actorOf(me);
        User target = platformUser(id);
        admin.requireManageLogin(actor, target);
        if (!Objects.equals(req.roleId(), target.getRoleId())) {
            target.setRoleId(givable(actor, req.roleId()).getId());
        }
        admin.applyDetails(target, req.name(), req.email(), req.mobileNumber());
        return admin.summary(users.save(target));
    }

    @PostMapping("/{id}/reset-password")
    public TemporaryPasswordResponse resetPassword(@AuthenticationPrincipal AuthenticatedUser me,
            @PathVariable String id, @RequestBody ResetPasswordRequest req) {
        admin.resetPassword(access.actorOf(me), platformUser(id), req.temporaryPassword());
        return TemporaryPasswordResponse.reset(req.temporaryPassword());
    }

    @PostMapping("/{id}/disable")
    public UserSummary disable(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String id) {
        return admin.summary(admin.setEnabled(access.actorOf(me), me, platformUser(id), false, EnableLevel.PLATFORM));
    }

    @PostMapping("/{id}/enable")
    public UserSummary enable(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String id) {
        return admin.summary(admin.setEnabled(access.actorOf(me), me, platformUser(id), true, EnableLevel.PLATFORM));
    }

    /** A platform user by id; customer users are not found here. */
    private User platformUser(String id) {
        return users.findById(id)
                .filter(u -> u.getCustomerId() == null)
                .orElseThrow(() -> new NotFoundException("user not found"));
    }

    /** A PLATFORM role the actor may give: 400 if it is not a PLATFORM role, 403 if the policy refuses. */
    private Role givable(Actor actor, String roleId) {
        Role role = roles.byId(roleId)
                .filter(r -> r.getLevel() == RoleLevel.PLATFORM)
                .orElseThrow(() -> new ValidationException("roleId", "must be a platform role"));
        if (!policy.canGivePlatformRole(actor, role)) {
            throw new ForbiddenException("you may not give the role " + role.getName());
        }
        return role;
    }

    private boolean isSuperAdmin(User u) {
        return roles.byId(u.getRoleId()).filter(r -> BuiltInRoles.SUPER_ADMIN.equals(r.getName())).isPresent();
    }
}
