package me.nageshk.omnichannel.tenant;

import me.nageshk.omnichannel.auth.AuthenticatedUser;
import me.nageshk.omnichannel.enablement.EnableLevel;
import me.nageshk.omnichannel.enablement.Enablement;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import me.nageshk.omnichannel.access.AccessService;
import me.nageshk.omnichannel.access.AssignmentPolicy;
import me.nageshk.omnichannel.access.AssignmentPolicy.Actor;
import me.nageshk.omnichannel.access.BuiltInRoles;
import me.nageshk.omnichannel.access.Role;
import me.nageshk.omnichannel.access.RoleCache;
import me.nageshk.omnichannel.access.RoleLevel;
import me.nageshk.omnichannel.api.ForbiddenException;
import me.nageshk.omnichannel.api.NotFoundException;
import me.nageshk.omnichannel.api.ValidationException;
import me.nageshk.omnichannel.user.User;
import me.nageshk.omnichannel.user.UserAdminService;
import me.nageshk.omnichannel.user.UserAdminService.NewLogin;
import me.nageshk.omnichannel.user.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

/**
 * Customers as platform staff manage them: create (with first workspace, its {@code General} team
 * and the owner), list, rename, disable, and the customer's admins (COMPANY-role holders).
 * Authorities are checked by the controller; which COMPANY role may be given, and which admin login
 * may be managed, by {@link AssignmentPolicy}.
 */
@Service
public class CustomerService {

    public static final int PAGE_SIZE = 50;
    public static final String FIRST_TEAM = "General";

    public record Created(Customer customer, User admin) {}

    private final CustomerRepository customers;
    private final WorkspaceRepository workspaces;
    private final TeamRepository teams;
    private final UserRepository users;
    private final UserAdminService userAdmin;
    private final AccessService access;
    private final AssignmentPolicy policy;
    private final RoleCache roles;
    private final Enablement enablement;

    public CustomerService(CustomerRepository customers, WorkspaceRepository workspaces, TeamRepository teams,
            UserRepository users, UserAdminService userAdmin, AccessService access, AssignmentPolicy policy,
            RoleCache roles, Enablement enablement) {
        this.enablement = enablement;
        this.customers = customers;
        this.workspaces = workspaces;
        this.teams = teams;
        this.users = users;
        this.userAdmin = userAdmin;
        this.access = access;
        this.policy = policy;
        this.roles = roles;
    }

    /**
     * Inserts the customer, its first workspace (named as the customer unless given), a team
     * {@code General} in it and the owner with {@code CUSTOMER_ADMIN}. Mongo here is standalone, so
     * instead of a transaction: if any step fails, what was inserted is deleted in reverse order and
     * the failure is rethrown.
     */
    public Created create(Actor actor, String createdBy, String name, String workspaceName, NewLogin owner) {
        String customerName = requireName(name);
        Role ownerRole = companyRole(actor, null);
        userAdmin.check(owner);

        Deque<Runnable> undo = new ArrayDeque<>();
        try {
            Customer c = new Customer();
            c.setName(customerName);
            c.setActive(true);
            c.setCreatedBy(createdBy);
            Customer customer = customers.insert(c);
            undo.push(() -> customers.deleteById(customer.getId()));

            Workspace w = new Workspace();
            w.setCustomerId(customer.getId());
            w.setName(workspaceName == null || workspaceName.isBlank() ? customerName : workspaceName.trim());
            Workspace workspace = workspaces.insert(w);
            undo.push(() -> workspaces.deleteById(workspace.getId()));

            Team t = new Team();
            t.setCustomerId(customer.getId());
            t.setWorkspaceId(workspace.getId());
            t.setName(FIRST_TEAM);
            Team team = teams.insert(t);
            undo.push(() -> teams.deleteById(team.getId()));

            User admin = userAdmin.create(owner, customer.getId(), ownerRole.getId(), List.of());
            return new Created(customer, admin);
        } catch (RuntimeException e) {
            while (!undo.isEmpty()) {
                try {
                    undo.pop().run();
                } catch (RuntimeException cleanup) {
                    e.addSuppressed(cleanup);
                }
            }
            throw e;
        }
    }

    /** One page of customers by name; {@code q} matches anywhere in the name, ignoring case. */
    public Page<Customer> list(String q, Boolean active, int page) {
        if (page < 0) throw new ValidationException("page", "must be 0 or more");
        Pageable pageable = PageRequest.of(page, PAGE_SIZE, Sort.by("name").and(Sort.by("id")));
        String name = q == null ? "" : q.trim();
        return active == null
                ? customers.findByNameContainingIgnoreCase(name, pageable)
                : customers.findByNameContainingIgnoreCaseAndActive(name, active, pageable);
    }

    /** @throws NotFoundException if there is no such customer */
    public Customer require(String id) {
        return customers.findById(id).orElseThrow(() -> new NotFoundException("customer not found"));
    }

    public List<Workspace> workspacesOf(Customer c) {
        return workspaces.findByCustomerIdOrderByName(c.getId());
    }

    /** The customer's users holding a COMPANY role, by name. */
    public List<User> adminsOf(Customer c) {
        return users.findByCustomerId(c.getId()).stream()
                .filter(this::isAdmin)
                .sorted(Comparator.comparing(User::getName, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    public Customer rename(String id, String name) {
        Customer c = require(id);
        c.setName(requireName(name));
        return customers.save(c);
    }

    /**
     * Flips the customer only: its users are refused at login and on their next request while it is
     * disabled, and their tokens work again once it is enabled. Platform staff act at PLATFORM.
     */
    public Customer setEnabled(String id, boolean enabled, AuthenticatedUser me) {
        Customer c = require(id);
        if (c.isActive() == enabled) return c;
        if (enabled) {
            enablement.checkEnable(c.getDisabled(), EnableLevel.PLATFORM);
            c.setDisabled(null);
        } else {
            c.setDisabled(enablement.disable(c.getDisabled(), me, EnableLevel.PLATFORM));
        }
        c.setActive(enabled);
        Customer saved = customers.save(c);
        enablement.record("CUSTOMER", id, !enabled, enabled, me, EnableLevel.PLATFORM);
        return saved;
    }

    /** Every login of the customer, by name. */
    public List<User> usersOf(Customer c) {
        return users.findByCustomerId(c.getId()).stream()
                .sorted(java.util.Comparator.comparing(User::getName, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    /** A login of the customer; 404 for anyone else's. */
    public User requireUser(String customerId, String userId) {
        return users.findById(userId)
                .filter(u -> customerId.equals(u.getCustomerId()))
                .orElseThrow(() -> new NotFoundException("user not found"));
    }

    /** Another admin for an existing customer; {@code roleId} null means {@code CUSTOMER_ADMIN}. */
    public User addAdmin(Actor actor, String customerId, NewLogin login, String roleId) {
        Customer c = require(customerId);
        Role role = companyRole(actor, roleId);
        return userAdmin.create(login, c.getId(), role.getId(), List.of());
    }

    /**
     * An admin (COMPANY-role holder) of the customer.
     *
     * @throws NotFoundException if the customer does not exist, or the user is not one of its admins
     */
    public User requireAdmin(String customerId, String userId) {
        Customer c = require(customerId);
        return users.findById(userId)
                .filter(u -> c.getId().equals(u.getCustomerId()) && isAdmin(u))
                .orElseThrow(() -> new NotFoundException("admin not found"));
    }

    /** The COMPANY roles the actor may give, by name. */
    public List<Role> assignableRoles(Actor actor) {
        return policy.assignableCompanyRoles(actor, roles.all()).stream()
                .sorted(Comparator.comparing(Role::getName))
                .toList();
    }

    /** A COMPANY role the actor may give: 400 if not a COMPANY role, 403 if the policy refuses. */
    private Role companyRole(Actor actor, String roleId) {
        Role role = (roleId == null ? roles.byName(BuiltInRoles.CUSTOMER_ADMIN) : roles.byId(roleId))
                .filter(r -> r.getLevel() == RoleLevel.COMPANY)
                .orElseThrow(() -> roleId == null
                        ? new IllegalStateException("built-in role " + BuiltInRoles.CUSTOMER_ADMIN + " is missing")
                        : new ValidationException("roleId", "must be a company role"));
        if (!policy.canGiveCompanyRole(actor, role)) {
            throw new ForbiddenException("you may not give the role " + role.getName());
        }
        return role;
    }

    private boolean isAdmin(User u) {
        return access.targetOf(u).companyRole() != null;
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank()) throw new ValidationException("name", "must not be blank");
        return name.trim();
    }
}
