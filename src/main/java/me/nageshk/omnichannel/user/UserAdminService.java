package me.nageshk.omnichannel.user;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import me.nageshk.omnichannel.access.AccessService;
import me.nageshk.omnichannel.access.AssignmentPolicy;
import me.nageshk.omnichannel.access.AssignmentPolicy.Actor;
import me.nageshk.omnichannel.access.RoleCache;
import me.nageshk.omnichannel.api.DuplicateException;
import me.nageshk.omnichannel.api.ForbiddenException;
import me.nageshk.omnichannel.api.ValidationException;
import me.nageshk.omnichannel.tenant.Workspace;
import me.nageshk.omnichannel.tenant.WorkspaceRepository;
import me.nageshk.omnichannel.user.UserService.RoleRef;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Operations on other people's logins, shared by the platform-user, customer and workspace-user
 * APIs: create with a temporary password, edit details, reset the password, deactivate/reactivate.
 * The operations on an existing login check the whole-login rule of {@link AssignmentPolicy}
 * themselves; which role a caller may give is checked by the caller, since it depends on the scope.
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

    public UserAdminService(UserRepository users, WorkspaceRepository workspaces, AccessService access,
            AssignmentPolicy policy, PasswordPolicy passwords, PasswordEncoder encoder, RoleCache roles) {
        this.users = users;
        this.workspaces = workspaces;
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
    public User setActive(Actor actor, User target, boolean active) {
        if (!policy.canDeactivateLogin(actor, access.targetOf(target))) {
            throw new ForbiddenException("you may not " + (active ? "reactivate" : "deactivate") + " this user");
        }
        if (target.isActive() == active) return target;
        target.setActive(active);
        if (!active) target.setTokenVersion(target.getTokenVersion() + 1);
        return users.save(target);
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
                u.getId(), u.getName(), u.getUsername(), u.getEmail(), u.getMobileNumber(), u.isActive(),
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
