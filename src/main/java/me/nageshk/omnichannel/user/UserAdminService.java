package me.nageshk.omnichannel.user;

import me.nageshk.omnichannel.auth.AuthenticatedUser;
import me.nageshk.omnichannel.enablement.EnableLevel;
import me.nageshk.omnichannel.enablement.Enablement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import me.nageshk.omnichannel.access.AccessService;
import me.nageshk.omnichannel.access.AssignmentPolicy;
import me.nageshk.omnichannel.access.AssignmentPolicy.Actor;
import me.nageshk.omnichannel.access.AssignmentPolicy.Target;
import me.nageshk.omnichannel.access.Role;
import me.nageshk.omnichannel.access.RoleCache;
import me.nageshk.omnichannel.access.RoleLevel;
import me.nageshk.omnichannel.api.AlreadyAllBrandsException;
import me.nageshk.omnichannel.api.AlreadyInWorkspaceException;
import me.nageshk.omnichannel.api.DuplicateException;
import me.nageshk.omnichannel.api.ForbiddenException;
import me.nageshk.omnichannel.api.LastAccessException;
import me.nageshk.omnichannel.api.NotFoundException;
import me.nageshk.omnichannel.api.ValidationException;
import me.nageshk.omnichannel.tenant.Team;
import me.nageshk.omnichannel.tenant.TeamRepository;
import me.nageshk.omnichannel.tenant.Workspace;
import me.nageshk.omnichannel.tenant.WorkspaceRepository;
import me.nageshk.omnichannel.user.UserService.RoleRef;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Operations on other people's logins, shared by the platform-user, customer and workspace-user
 * APIs: create with a temporary password, edit details, reset the password, disable/enable.
 * The operations on an existing login check the whole-login rule of {@link AssignmentPolicy}
 * themselves; which role a caller may give is checked by the caller, since it depends on the scope,
 * except for the workspace operations below, which check the BRAND-role rules themselves.
 *
 * <p>Access to a workspace means a BRAND role in it or a COMPANY role in its customer. A user with
 * a COMPANY role is never given a BRAND role as well.
 */
@Service
public class UserAdminService {

    /** What the creator types for a new login. */
    public record NewLogin(String name, String username, String email, String mobileNumber, String temporaryPassword) {}

    private final UserRepository users;
    private final WorkspaceRepository workspaces;
    private final AccessService access;
    private final AssignmentPolicy policy;
    private final PasswordPolicy passwords;
    private final PasswordEncoder encoder;
    private final RoleCache roles;
    private final TeamRepository teams;
    private final Enablement enablement;

    public UserAdminService(UserRepository users, WorkspaceRepository workspaces, AccessService access,
            AssignmentPolicy policy, PasswordPolicy passwords, PasswordEncoder encoder, RoleCache roles,
            TeamRepository teams, Enablement enablement) {
        this.enablement = enablement;
        this.users = users;
        this.workspaces = workspaces;
        this.teams = teams;
        this.access = access;
        this.policy = policy;
        this.passwords = passwords;
        this.encoder = encoder;
        this.roles = roles;
    }

    /**
     * Rejects a new login that could never be saved, without touching the database: blank name or
     * username, a username with spaces, a temporary password under the minimum. Uniqueness is
     * checked by {@link #create}.
     */
    public void check(NewLogin login) {
        Map<String, String> problems = new LinkedHashMap<>();
        if (isBlank(login.name())) problems.put("name", "must not be blank");
        String username = normalizeUsername(login.username());
        if (username == null) problems.put("username", "must not be blank");
        else if (username.chars().anyMatch(Character::isWhitespace)) problems.put("username", "must not contain spaces");
        if (!problems.isEmpty()) throw new ValidationException(problems);
        passwords.check(login.temporaryPassword(), null);
    }

    /**
     * Saves a new active login that must change its temporary password on first login.
     *
     * @throws DuplicateException (field {@code username}) if the lower-cased username is taken
     */
    public User create(NewLogin login, String customerId, String roleId, List<WorkspaceRole> workspaceRoles) {
        check(login);
        String username = normalizeUsername(login.username());
        if (users.existsByUsername(username)) throw duplicateUsername();
        User u = new User();
        u.setName(login.name().trim());
        u.setUsername(username);
        u.setEmail(trimToNull(login.email()));
        u.setMobileNumber(trimToNull(login.mobileNumber()));
        u.setPassword(encoder.encode(login.temporaryPassword()));
        u.setCustomerId(customerId);
        u.setRoleId(roleId);
        u.setWorkspaceRoles(workspaceRoles);
        u.setMustChangePassword(true);
        u.setActive(true);
        u.setTwoFactor(TwoFactor.disabled());
        try {
            return users.insert(u);
        } catch (DuplicateKeyException e) {
            throw duplicateUsername();
        }
    }

    /**
     * The whole-login rule (edit details, change a platform role, reset password). Check it against
     * the user as stored, before changing anything.
     *
     * @throws ForbiddenException if the actor may not manage this login
     */
    public void requireManageLogin(Actor actor, User target) {
        if (!policy.canManageLogin(actor, access.targetOf(target))) {
            throw new ForbiddenException("you may not manage this user");
        }
    }

    /** Sets name, email and mobile number, without saving; the caller checks {@link #requireManageLogin} first. */
    public void applyDetails(User target, String name, String email, String mobileNumber) {
        if (isBlank(name)) throw new ValidationException("name", "must not be blank");
        target.setName(name.trim());
        target.setEmail(trimToNull(email));
        target.setMobileNumber(trimToNull(mobileNumber));
    }

    /** New temporary password, forced change at next login, every issued token revoked. */
    public User resetPassword(Actor actor, User target, String temporaryPassword) {
        requireManageLogin(actor, target);
        passwords.check(temporaryPassword, null);
        target.setPassword(encoder.encode(temporaryPassword));
        target.setMustChangePassword(true);
        target.setTokenVersion(target.getTokenVersion() + 1);
        return users.save(target);
    }

    /** Deactivating revokes every issued token; reactivating does not bring them back. */
    public User setEnabled(Actor actor, AuthenticatedUser me, User target, boolean enabled, EnableLevel level) {
        if (!policy.canDisableLogin(actor, access.targetOf(target))) {
            throw new ForbiddenException("you may not " + (enabled ? "enable" : "disable") + " this user");
        }
        boolean before = target.isActive();
        if (enabled) {
            if (before) return target;
            enablement.checkEnable(target.getDisabled(), level);
            target.setDisabled(null);
        } else {
            target.setDisabled(enablement.disable(target.getDisabled(), me, level));
            if (before) target.setTokenVersion(target.getTokenVersion() + 1);
        }
        target.setActive(enabled);
        User saved = users.save(target);
        if (before != enabled) enablement.record("USER", target.getId(), before, enabled, me, level);
        return saved;
    }

    /** The customer's users with access to the workspace (a role there, or a COMPANY role), by name. */
    public List<User> withAccessTo(String customerId, String workspaceId) {
        return users.findByCustomerId(customerId).stream()
                .filter(u -> hasAccess(access.targetOf(u), workspaceId))
                .sorted(Comparator.comparing(User::getName, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    /** True if the user holds a COMPANY role, and so acts in every workspace of its customer. */
    public boolean hasCompanyRole(User u) {
        return access.targetOf(u).companyRole() != null;
    }

    /** @throws NotFoundException if the user does not exist or is not the customer's */
    public User requireInCustomer(String customerId, String userId) {
        return users.findById(userId)
                .filter(u -> customerId != null && customerId.equals(u.getCustomerId()))
                .orElseThrow(() -> new NotFoundException("user not found"));
    }

    /** @throws NotFoundException unless the user is the customer's and has access to the workspace */
    public User requireInWorkspace(String customerId, String workspaceId, String userId) {
        User u = requireInCustomer(customerId, userId);
        if (!hasAccess(access.targetOf(u), workspaceId)) throw new NotFoundException("user not found");
        return u;
    }

    /** A new login of the customer whose only access is the given BRAND role in the workspace. */
    public User createInWorkspace(Actor actor, NewLogin login, String customerId, String workspaceId, String roleId) {
        Role role = givableBrandRole(actor, workspaceId, roleId);
        return create(login, customerId, null, List.of(new WorkspaceRole(workspaceId, role.getId())));
    }

    /**
     * Gives an existing user of the customer a BRAND role in a workspace where it has none.
     *
     * @throws AlreadyAllBrandsException if the user holds a COMPANY role
     * @throws AlreadyInWorkspaceException if the user already has a role in the workspace
     */
    public User addToWorkspace(Actor actor, User target, String workspaceId, String roleId) {
        Target t = access.targetOf(target);
        if (t.companyRole() != null) throw alreadyAllBrands();
        if (t.workspaceRoles().containsKey(workspaceId)
                || target.getWorkspaceRoles().stream().anyMatch(wr -> workspaceId.equals(wr.workspaceId()))) {
            throw new AlreadyInWorkspaceException("this user already has a role in this workspace");
        }
        Role role = givableBrandRole(actor, workspaceId, roleId);
        List<WorkspaceRole> updated = new ArrayList<>(target.getWorkspaceRoles());
        updated.add(new WorkspaceRole(workspaceId, role.getId()));
        target.setWorkspaceRoles(updated);
        return users.save(target);
    }

    /**
     * Edits a user from a workspace's page. Changed details need the whole-login rule; a changed role
     * ({@code roleId} not null and not the current one) needs the one-workspace rule for the current
     * role and the right to give the new one. Everything is checked before anything is saved.
     */
    public User updateInWorkspace(Actor actor, User target, String workspaceId, String name, String email,
            String mobileNumber, String roleId) {
        boolean detailsChanged = !Objects.equals(name == null ? null : name.trim(), target.getName())
                || !Objects.equals(trimToNull(email), target.getEmail())
                || !Objects.equals(trimToNull(mobileNumber), target.getMobileNumber());
        if (detailsChanged) requireManageLogin(actor, target);

        String currentRoleId = target.getWorkspaceRoles().stream()
                .filter(wr -> workspaceId.equals(wr.workspaceId()))
                .map(WorkspaceRole::roleId)
                .findFirst().orElse(null);
        Role newRole = null;
        if (roleId != null && !roleId.equals(currentRoleId)) {
            Target t = access.targetOf(target);
            if (t.companyRole() != null) throw alreadyAllBrands();
            if (!policy.canChangeInWorkspace(actor, t, workspaceId)) {
                throw new ForbiddenException("you may not change this user's role here");
            }
            newRole = givableBrandRole(actor, workspaceId, roleId);
        }

        if (detailsChanged) applyDetails(target, name, email, mobileNumber);
        if (newRole != null) {
            String newRoleId = newRole.getId();
            target.setWorkspaceRoles(new ArrayList<>(target.getWorkspaceRoles().stream()
                    .map(wr -> workspaceId.equals(wr.workspaceId()) ? new WorkspaceRole(workspaceId, newRoleId) : wr)
                    .toList()));
        }
        return detailsChanged || newRole != null ? users.save(target) : target;
    }

    /**
     * Takes away the user's role in the workspace and its membership of the workspace's teams.
     *
     * @throws ForbiddenException if the one-workspace rule refuses
     * @throws AlreadyAllBrandsException if the user's access comes from a COMPANY role
     * @throws LastAccessException if the user would be left with no role anywhere and no COMPANY role
     */
    public void removeFromWorkspace(Actor actor, User target, String workspaceId) {
        Target t = access.targetOf(target);
        if (!policy.canChangeInWorkspace(actor, t, workspaceId)) {
            throw new ForbiddenException("you may not remove this user from this workspace");
        }
        if (t.companyRole() != null) throw alreadyAllBrands();
        List<WorkspaceRole> remaining = target.getWorkspaceRoles().stream()
                .filter(wr -> !workspaceId.equals(wr.workspaceId()))
                .toList();
        if (remaining.isEmpty()) {
            throw new LastAccessException("this is the user's only access; disable the user instead");
        }
        target.setWorkspaceRoles(new ArrayList<>(remaining));
        users.save(target);
        for (Team team : teams.findByWorkspaceIdOrderByName(workspaceId)) {
            if (team.getMemberIds().remove(target.getId())) teams.save(team);
        }
    }

    /** The BRAND roles the actor may give in the workspace, by name. */
    public List<Role> assignableBrandRoles(Actor actor, String workspaceId) {
        return policy.assignableBrandRoles(actor, workspaceId, roles.all()).stream()
                .sorted(Comparator.comparing(Role::getName))
                .toList();
    }

    /** A BRAND role the actor may give in the workspace: 400 if not a BRAND role, 403 if the policy refuses. */
    private Role givableBrandRole(Actor actor, String workspaceId, String roleId) {
        Role role = roles.byId(roleId)
                .filter(r -> r.getLevel() == RoleLevel.BRAND)
                .orElseThrow(() -> new ValidationException("roleId", "must be a brand role"));
        if (!policy.canGiveBrandRole(actor, workspaceId, role)) {
            throw new ForbiddenException("you may not give the role " + role.getName());
        }
        return role;
    }

    private static boolean hasAccess(Target t, String workspaceId) {
        return t.companyRole() != null || t.workspaceRoles().containsKey(workspaceId);
    }

    private static AlreadyAllBrandsException alreadyAllBrands() {
        return new AlreadyAllBrandsException("this user has a company role and already acts in every workspace");
    }

    public UserSummary summary(User u) {
        return summaries(List.of(u)).get(0);
    }

    /** Summaries in the given order, with workspace names looked up once for the whole list. */
    public List<UserSummary> summaries(List<User> list) {
        Set<String> workspaceIds = list.stream()
                .flatMap(u -> u.getWorkspaceRoles().stream())
                .map(WorkspaceRole::workspaceId)
                .collect(Collectors.toSet());
        Map<String, String> names = workspaces.findAllById(workspaceIds).stream()
                .collect(Collectors.toMap(Workspace::getId, Workspace::getName));
        return list.stream().map(u -> new UserSummary(
                u.getId(), u.getName(), u.getUsername(), u.getEmail(), u.getMobileNumber(), u.isActive(), u.getDisabled(),
                roleRef(u.getRoleId()),
                u.getWorkspaceRoles().stream()
                        .map(wr -> new UserSummary.WorkspaceRoleView(
                                wr.workspaceId(), names.get(wr.workspaceId()), roleRef(wr.roleId())))
                        .toList(),
                u.isMustChangePassword())).toList();
    }

    public RoleRef roleRef(String roleId) {
        return roles.byId(roleId).map(r -> new RoleRef(r.getId(), r.getName())).orElse(null);
    }

    /** Usernames are stored trimmed and lower-case; null if blank. */
    public static String normalizeUsername(String username) {
        return isBlank(username) ? null : username.trim().toLowerCase(Locale.ROOT);
    }

    private static DuplicateException duplicateUsername() {
        return new DuplicateException("username", "this username is already taken");
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String trimToNull(String s) {
        return isBlank(s) ? null : s.trim();
    }
}
