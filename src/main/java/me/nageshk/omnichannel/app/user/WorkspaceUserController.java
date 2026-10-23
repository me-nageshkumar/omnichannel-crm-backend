package me.nageshk.omnichannel.app.user;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import java.util.List;
import java.util.stream.IntStream;
import me.nageshk.omnichannel.common.api.ValidationException;
import me.nageshk.omnichannel.db.enablement.Disabled;
import me.nageshk.omnichannel.db.user.User;
import me.nageshk.omnichannel.security.access.AccessService;
import me.nageshk.omnichannel.security.access.AssignmentPolicy.Actor;
import me.nageshk.omnichannel.security.access.AssignmentPolicy;
import me.nageshk.omnichannel.security.auth.AuthenticatedUser;
import me.nageshk.omnichannel.security.enablement.Enablement;
import me.nageshk.omnichannel.security.user.TemporaryPasswordResponse;
import me.nageshk.omnichannel.security.user.UserAdminService.NewLogin;
import me.nageshk.omnichannel.security.user.UserAdminService;
import me.nageshk.omnichannel.security.user.UserService.RoleRef;
import me.nageshk.omnichannel.security.user.UserService;
import me.nageshk.omnichannel.security.user.UserSummary;
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

/**
 * Users of one workspace: those with a role there plus the customer's COMPANY-role holders ("all
 * brands"). {@code user:view} to list, {@code user:manage} for the rest, with the strict-subset rule
 * of {@link AssignmentPolicy}. A workspace the caller cannot reach, or a user who is not the
 * caller's customer's or has no access to the workspace, answers 404.
 */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}")
public class WorkspaceUserController {

    /** A row of the list: the summary, whether its access comes from a COMPANY role, and whether the caller may manage the login. */
    public record WorkspaceUser(@JsonUnwrapped UserSummary user, boolean allBrands, boolean manageable,
            Disabled brandDisabled) {}

    /** Either a new login ({@code userId} absent) or an existing user of the customer ({@code userId}), with a role here. */
    public record AddRequest(String userId, String name, String username, String email, String mobileNumber,
            String temporaryPassword, String roleId) {}

    /** {@code roleId} null leaves the role here unchanged. */
    public record UpdateRequest(String name, String email, String mobileNumber, String roleId) {}

    public record ResetPasswordRequest(String temporaryPassword) {}

    private final UserAdminService admin;
    private final AccessService access;
    private final AssignmentPolicy policy;
    private final Enablement enablement;

    public WorkspaceUserController(UserAdminService admin, AccessService access, AssignmentPolicy policy,
            Enablement enablement) {
        this.enablement = enablement;
        this.admin = admin;
        this.access = access;
        this.policy = policy;
    }

    @GetMapping("/users")
    @PreAuthorize("@access.has(#workspaceId, 'user:view')")
    public List<WorkspaceUser> list(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String workspaceId) {
        Actor actor = access.actorOf(me);
        List<User> list = admin.withAccessTo(me.customerId(), workspaceId);
        List<UserSummary> summaries = admin.summaries(list);
        return IntStream.range(0, list.size())
                .mapToObj(i -> new WorkspaceUser(summaries.get(i), admin.hasCompanyRole(list.get(i)),
                        policy.canManageLogin(actor, access.targetOf(list.get(i))),
                        UserAdminService.brandDisabled(list.get(i), workspaceId)))
                .toList();
    }

    /** {@code { user, temporaryPassword }} for a new login, {@code { user }} for an existing one. */
    @PostMapping("/users")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@access.has(#workspaceId, 'user:manage')")
    public TemporaryPasswordResponse add(@AuthenticationPrincipal AuthenticatedUser me,
            @PathVariable String workspaceId, @RequestBody AddRequest req) {
        if (req.roleId() == null || req.roleId().isBlank()) throw new ValidationException("roleId", "must not be blank");
        Actor actor = access.actorOf(me);
        if (req.userId() != null) {
            User target = admin.requireInCustomer(me.customerId(), req.userId());
            return TemporaryPasswordResponse.created(
                    admin.summary(admin.addToWorkspace(actor, target, workspaceId, req.roleId())), null);
        }
        User created = admin.createInWorkspace(actor,
                new NewLogin(req.name(), req.username(), req.email(), req.mobileNumber(), req.temporaryPassword()),
                me.customerId(), workspaceId, req.roleId());
        return TemporaryPasswordResponse.created(admin.summary(created), req.temporaryPassword());
    }

    @PutMapping("/users/{userId}")
    @PreAuthorize("@access.has(#workspaceId, 'user:manage')")
    public UserSummary update(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String workspaceId,
            @PathVariable String userId, @RequestBody UpdateRequest req) {
        User target = admin.requireInWorkspace(me.customerId(), workspaceId, userId);
        return admin.summary(admin.updateInWorkspace(access.actorOf(me), target, workspaceId,
                req.name(), req.email(), req.mobileNumber(), req.roleId()));
    }

    @DeleteMapping("/users/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@access.has(#workspaceId, 'user:manage')")
    public void remove(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String workspaceId,
            @PathVariable String userId) {
        User target = admin.requireInWorkspace(me.customerId(), workspaceId, userId);
        admin.removeFromWorkspace(access.actorOf(me), target, workspaceId);
    }

    @PostMapping("/users/{userId}/reset-password")
    @PreAuthorize("@access.has(#workspaceId, 'user:manage')")
    public TemporaryPasswordResponse resetPassword(@AuthenticationPrincipal AuthenticatedUser me,
            @PathVariable String workspaceId, @PathVariable String userId, @RequestBody ResetPasswordRequest req) {
        User target = admin.requireInWorkspace(me.customerId(), workspaceId, userId);
        admin.resetPassword(access.actorOf(me), target, req.temporaryPassword());
        return TemporaryPasswordResponse.reset(req.temporaryPassword());
    }

    @PostMapping("/users/{userId}/disable")
    @PreAuthorize("@access.has(#workspaceId, 'user:enable')")
    public WorkspaceUser disable(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String workspaceId,
            @PathVariable String userId) {
        return setBrandAccess(me, workspaceId, userId, false);
    }

    @PostMapping("/users/{userId}/enable")
    @PreAuthorize("@access.has(#workspaceId, 'user:enable')")
    public WorkspaceUser enable(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String workspaceId,
            @PathVariable String userId) {
        return setBrandAccess(me, workspaceId, userId, true);
    }

    private WorkspaceUser setBrandAccess(AuthenticatedUser me, String workspaceId, String userId, boolean enabled) {
        User target = admin.requireInWorkspace(me.customerId(), workspaceId, userId);
        Actor actor = access.actorOf(me);
        User saved = admin.setBrandAccessEnabled(actor, me, target, workspaceId, enabled,
                enablement.levelOf(me, "user:enable", workspaceId));
        return new WorkspaceUser(admin.summary(saved), admin.hasCompanyRole(saved),
                policy.canManageLogin(actor, access.targetOf(saved)), UserAdminService.brandDisabled(saved, workspaceId));
    }

    @GetMapping("/assignable-roles")
    @PreAuthorize("@access.has(#workspaceId, 'user:manage')")
    public List<RoleRef> assignableRoles(@AuthenticationPrincipal AuthenticatedUser me,
            @PathVariable String workspaceId) {
        return admin.assignableBrandRoles(access.actorOf(me), workspaceId).stream()
                .map(r -> new RoleRef(r.getId(), r.getName()))
                .toList();
    }
}
