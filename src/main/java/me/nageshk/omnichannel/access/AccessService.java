package me.nageshk.omnichannel.access;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import me.nageshk.omnichannel.api.NotFoundException;
import me.nageshk.omnichannel.auth.AuthenticatedUser;
import me.nageshk.omnichannel.tenant.Customer;
import me.nageshk.omnichannel.tenant.CustomerRepository;
import me.nageshk.omnichannel.tenant.Workspace;
import me.nageshk.omnichannel.tenant.WorkspaceRepository;
import me.nageshk.omnichannel.user.User;
import me.nageshk.omnichannel.user.WorkspaceRole;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/**
 * Who holds which authority, globally and per workspace. Global (PLATFORM / COMPANY) authorities
 * become the principal's {@code GrantedAuthority}s; BRAND authorities are checked per workspace,
 * from SpEL as {@code @access.has(#workspaceId, 'user:manage')}.
 *
 * <p>Access to a workspace means a BRAND role in it or a COMPANY role in its customer. Platform staff,
 * the Super Admin included, have no brand access in this slice. A workspace without access is
 * reported as not found, so other tenants' ids are not confirmed to exist.
 */
@Service("access")
public class AccessService {

    private final RoleCache roles;
    private final AuthorityCatalog catalog;
    private final CustomerRepository customers;
    private final WorkspaceRepository workspaces;

    public AccessService(RoleCache roles, AuthorityCatalog catalog, CustomerRepository customers, WorkspaceRepository workspaces) {
        this.roles = roles;
        this.catalog = catalog;
        this.customers = customers;
        this.workspaces = workspaces;
    }

    /** The principal for a user: its global authorities, and disabled if its customer is missing or inactive. */
    public AuthenticatedUser load(User user) {
        boolean customerActive = user.getCustomerId() == null
                || customers.findById(user.getCustomerId()).map(Customer::isActive).orElse(false);
        return new AuthenticatedUser(user, globalAuthorities(user), customerActive);
    }

    /**
     * Effective authorities in a workspace: the COMPANY role's COMPANY and BRAND codes plus the
     * workspace role's BRAND codes.
     *
     * @throws NotFoundException if the workspace does not exist, is another customer's, or the user
     *     has no access to it
     */
    public Set<String> inWorkspace(AuthenticatedUser user, String workspaceId) {
        NotFoundException notFound = new NotFoundException("workspace not found");
        if (user.customerId() == null || workspaceId == null) throw notFound;
        workspaces.findByIdAndCustomerId(workspaceId, user.customerId()).orElseThrow(() -> notFound);

        Optional<Role> companyRole = companyRole(user.roleId());
        Optional<Role> workspaceRole = user.workspaceRoles().stream()
                .filter(wr -> workspaceId.equals(wr.workspaceId()))
                .findFirst()
                .flatMap(wr -> roleAt(wr.roleId(), RoleLevel.BRAND));
        if (companyRole.isEmpty() && workspaceRole.isEmpty()) throw notFound;

        Set<String> effective = new HashSet<>();
        companyRole.ifPresent(r -> effective.addAll(codes(r, Set.of(RoleLevel.COMPANY, RoleLevel.BRAND))));
        workspaceRole.ifPresent(r -> effective.addAll(codes(r, Set.of(RoleLevel.BRAND))));
        return effective;
    }

    /**
     * For SpEL in {@code @PreAuthorize}: no access throws {@link NotFoundException} (404); access
     * without the authority returns false (403).
     */
    public boolean has(String workspaceId, String authority) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof AuthenticatedUser user)) return false;
        return inWorkspace(user, workspaceId).contains(authority);
    }

    /** The user's COMPANY role's COMPANY and BRAND codes; empty without a COMPANY role. */
    public Set<String> companyCodes(AuthenticatedUser user) {
        if (user.customerId() == null) return Set.of();
        return companyRole(user.roleId()).map(r -> codes(r, Set.of(RoleLevel.COMPANY, RoleLevel.BRAND))).orElse(Set.of());
    }

    /** The workspaces the user can act in, by name: all of its customer's with a COMPANY role, else its workspace roles'. */
    public List<Workspace> workspacesOf(AuthenticatedUser user) {
        if (user.customerId() == null) return List.of();
        if (companyRole(user.roleId()).isPresent()) {
            return workspaces.findByCustomerIdOrderByName(user.customerId());
        }
        Set<String> ids = user.workspaceRoles().stream()
                .filter(wr -> roleAt(wr.roleId(), RoleLevel.BRAND).isPresent())
                .map(WorkspaceRole::workspaceId)
                .collect(Collectors.toSet());
        return workspaces.findAllById(ids).stream()
                .filter(w -> user.customerId().equals(w.getCustomerId()))
                .sorted(Comparator.comparing(Workspace::getName))
                .toList();
    }

    /**
     * The acting user for {@link AssignmentPolicy}: its customer exactly as stored (never null for a
     * customer user), its granted global codes without the {@code SUPER_ADMIN} marker, and its
     * effective set in every workspace it can act in.
     */
    public AssignmentPolicy.Actor actorOf(AuthenticatedUser user) {
        Set<String> global = user.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(code -> !BuiltInRoles.SUPER_ADMIN.equals(code))
                .collect(Collectors.toSet());
        Map<String, Set<String>> perWorkspace = new HashMap<>();
        for (Workspace w : workspacesOf(user)) perWorkspace.put(w.getId(), inWorkspace(user, w.getId()));
        return new AssignmentPolicy.Actor(user.superAdmin(), user.customerId(), global, perWorkspace);
    }

    /**
     * The user being acted on, for {@link AssignmentPolicy}: its role resolved by level (PLATFORM →
     * {@code platformRole}, COMPANY → {@code companyRole}) and its BRAND workspace roles. Unknown
     * roles, and workspace roles not at BRAND level, are left out.
     */
    public AssignmentPolicy.Target targetOf(User user) {
        Role role = roles.byId(user.getRoleId()).orElse(null);
        Role platformRole = role != null && role.getLevel() == RoleLevel.PLATFORM ? role : null;
        Role companyRole = role != null && role.getLevel() == RoleLevel.COMPANY ? role : null;
        Map<String, Role> workspaceRoles = new HashMap<>();
        for (WorkspaceRole wr : user.getWorkspaceRoles()) {
            roleAt(wr.roleId(), RoleLevel.BRAND).ifPresent(r -> workspaceRoles.put(wr.workspaceId(), r));
        }
        return new AssignmentPolicy.Target(user.getCustomerId(), companyRole, platformRole, workspaceRoles);
    }

    /**
     * Super Admin: every PLATFORM code plus the {@code SUPER_ADMIN} marker, whatever is stored. Other
     * platform users: their role's PLATFORM codes. Customer users: their COMPANY role's COMPANY codes.
     */
    private Set<String> globalAuthorities(User user) {
        if (user.getCustomerId() == null) {
            Optional<Role> role = roleAt(user.getRoleId(), RoleLevel.PLATFORM);
            if (role.isPresent() && BuiltInRoles.SUPER_ADMIN.equals(role.get().getName())) {
                Set<String> all = catalog.all().stream()
                        .filter(a -> a.getLevel() == RoleLevel.PLATFORM)
                        .map(AuthorityDefinition::getCode)
                        .collect(Collectors.toCollection(HashSet::new));
                all.add(BuiltInRoles.SUPER_ADMIN);
                return all;
            }
            return role.map(r -> codes(r, Set.of(RoleLevel.PLATFORM))).orElse(Set.of());
        }
        return companyRole(user.getRoleId()).map(r -> codes(r, Set.of(RoleLevel.COMPANY))).orElse(Set.of());
    }

    private Optional<Role> companyRole(String roleId) {
        return roleAt(roleId, RoleLevel.COMPANY);
    }

    private Optional<Role> roleAt(String roleId, RoleLevel level) {
        return roles.byId(roleId).filter(r -> r.getLevel() == level);
    }

    /** The role's codes that are in the catalogue at one of the given levels; removed codes are ignored. */
    private Set<String> codes(Role role, Set<RoleLevel> levels) {
        return role.getAuthorities().stream()
                .filter(code -> catalog.levelOf(code).filter(levels::contains).isPresent())
                .collect(Collectors.toSet());
    }
}
