package me.nageshk.omnichannel.tenant;

import me.nageshk.omnichannel.tenant.WorkspaceController.WorkspaceView;
import me.nageshk.omnichannel.enablement.Disabled;
import me.nageshk.omnichannel.enablement.EnableLevel;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import me.nageshk.omnichannel.access.AccessService;
import me.nageshk.omnichannel.auth.AuthenticatedUser;
import me.nageshk.omnichannel.user.TemporaryPasswordResponse;
import me.nageshk.omnichannel.user.User;
import me.nageshk.omnichannel.user.UserAdminService;
import me.nageshk.omnichannel.user.UserAdminService.NewLogin;
import me.nageshk.omnichannel.user.UserService.RoleRef;
import me.nageshk.omnichannel.user.UserSummary;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Platform staff's customer pages. {@code customer:view} to read, {@code customer:create} to create
 * a customer with its owner, {@code customer:manage} for everything else, adding an admin to an
 * existing customer included.
 */
@RestController
@RequestMapping("/api/customers")
public class CustomerController {

    public record CustomerView(String id, String name, boolean active, Disabled disabled, Instant creationTime) {}

    public record PageView(List<CustomerView> items, long total, int page) {}

    public record Detail(CustomerView customer, List<WorkspaceView> workspaces, List<UserSummary> admins) {}

    public record AdminRequest(@NotBlank String name, @NotBlank String username, String email, String mobileNumber,
            String temporaryPassword) {
        NewLogin login() {
            return new NewLogin(name, username, email, mobileNumber, temporaryPassword);
        }
    }

    public record CreateRequest(@NotBlank String name, String workspaceName, @NotNull @Valid AdminRequest admin) {}

    public record CreateResponse(CustomerView customer, UserSummary user, String temporaryPassword) {}

    public record RenameRequest(@NotBlank String name) {}

    public record AddAdminRequest(@NotBlank String name, @NotBlank String username, String email, String mobileNumber,
            String temporaryPassword, String roleId) {}

    public record ResetPasswordRequest(String temporaryPassword) {}

    private final CustomerService service;
    private final UserAdminService userAdmin;
    private final AccessService access;
    private final WorkspaceService workspaceService;

    public CustomerController(CustomerService service, UserAdminService userAdmin, AccessService access,
            WorkspaceService workspaceService) {
        this.workspaceService = workspaceService;
        this.service = service;
        this.userAdmin = userAdmin;
        this.access = access;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('customer:view')")
    public PageView list(@RequestParam(required = false) String q, @RequestParam(required = false) Boolean active,
            @RequestParam(defaultValue = "0") int page) {
        Page<Customer> result = service.list(q, active, page);
        return new PageView(result.getContent().stream().map(CustomerController::view).toList(),
                result.getTotalElements(), page);
    }

    @GetMapping("/assignable-roles")
    @PreAuthorize("hasAuthority('customer:manage') or hasAuthority('customer:create')")
    public List<RoleRef> assignableRoles(@AuthenticationPrincipal AuthenticatedUser me) {
        return service.assignableRoles(access.actorOf(me)).stream()
                .map(r -> new RoleRef(r.getId(), r.getName()))
                .toList();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('customer:view')")
    public Detail detail(@PathVariable String id) {
        Customer c = service.require(id);
        return new Detail(view(c),
                service.workspacesOf(c).stream().map(WorkspaceController::view).toList(),
                userAdmin.summaries(service.adminsOf(c)));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('customer:create')")
    public CreateResponse create(@AuthenticationPrincipal AuthenticatedUser me, @Valid @RequestBody CreateRequest req) {
        CustomerService.Created created = service.create(access.actorOf(me), me.userId(), req.name(),
                req.workspaceName(), req.admin().login());
        return new CreateResponse(view(created.customer()), userAdmin.summary(created.admin()),
                req.admin().temporaryPassword());
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('customer:manage')")
    public CustomerView rename(@PathVariable String id, @Valid @RequestBody RenameRequest req) {
        return view(service.rename(id, req.name()));
    }

    @PostMapping("/{id}/disable")
    @PreAuthorize("hasAuthority('customer:manage')")
    public CustomerView disable(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String id) {
        return view(service.setEnabled(id, false, me));
    }

    @PostMapping("/{id}/enable")
    @PreAuthorize("hasAuthority('customer:manage')")
    public CustomerView enable(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String id) {
        return view(service.setEnabled(id, true, me));
    }

    @PostMapping("/{id}/workspaces/{workspaceId}/disable")
    @PreAuthorize("hasAuthority('customer:manage')")
    public WorkspaceView disableWorkspace(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String id,
            @PathVariable String workspaceId) {
        return WorkspaceController.view(workspaceService.setEnabled(service.require(id).getId(), workspaceId, false, me,
                EnableLevel.PLATFORM));
    }

    @PostMapping("/{id}/workspaces/{workspaceId}/enable")
    @PreAuthorize("hasAuthority('customer:manage')")
    public WorkspaceView enableWorkspace(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String id,
            @PathVariable String workspaceId) {
        return WorkspaceController.view(workspaceService.setEnabled(service.require(id).getId(), workspaceId, true, me,
                EnableLevel.PLATFORM));
    }

    @GetMapping("/{id}/users")
    @PreAuthorize("hasAuthority('customer:view')")
    public List<UserSummary> users(@PathVariable String id) {
        return userAdmin.summaries(service.usersOf(service.require(id)));
    }

    @PostMapping("/{id}/admins")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('customer:manage')")
    public TemporaryPasswordResponse addAdmin(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String id,
            @Valid @RequestBody AddAdminRequest req) {
        User created = service.addAdmin(access.actorOf(me), id,
                new NewLogin(req.name(), req.username(), req.email(), req.mobileNumber(), req.temporaryPassword()),
                req.roleId());
        return TemporaryPasswordResponse.created(userAdmin.summary(created), req.temporaryPassword());
    }

    @PostMapping("/{id}/admins/{userId}/reset-password")
    @PreAuthorize("hasAuthority('customer:manage')")
    public TemporaryPasswordResponse resetAdminPassword(@AuthenticationPrincipal AuthenticatedUser me,
            @PathVariable String id, @PathVariable String userId, @RequestBody ResetPasswordRequest req) {
        userAdmin.resetPassword(access.actorOf(me), service.requireAdmin(id, userId), req.temporaryPassword());
        return TemporaryPasswordResponse.reset(req.temporaryPassword());
    }

    @PostMapping("/{id}/users/{userId}/disable")
    @PreAuthorize("hasAuthority('customer:manage')")
    public UserSummary disableUser(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String id,
            @PathVariable String userId) {
        return userAdmin.summary(userAdmin.setEnabled(access.actorOf(me), me, service.requireUser(id, userId), false,
                EnableLevel.PLATFORM));
    }

    @PostMapping("/{id}/users/{userId}/enable")
    @PreAuthorize("hasAuthority('customer:manage')")
    public UserSummary enableUser(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String id,
            @PathVariable String userId) {
        return userAdmin.summary(userAdmin.setEnabled(access.actorOf(me), me, service.requireUser(id, userId), true,
                EnableLevel.PLATFORM));
    }

    private static CustomerView view(Customer c) {
        return new CustomerView(c.getId(), c.getName(), c.isActive(), c.getDisabled(), c.getCreationTime());
    }
}
