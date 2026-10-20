package me.nageshk.omnichannel.access;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import me.nageshk.omnichannel.api.DuplicateException;
import me.nageshk.omnichannel.api.ForbiddenException;
import me.nageshk.omnichannel.api.NotFoundException;
import me.nageshk.omnichannel.api.RoleInUseException;
import me.nageshk.omnichannel.api.ValidationException;
import me.nageshk.omnichannel.user.UserRepository;
import org.springframework.stereotype.Service;

/** Super Admin management of roles: validation, audit, cache eviction and the edit preview. */
@Service
public class RoleService {

    public static final Pattern NAME = Pattern.compile("^[A-Z][A-Z0-9_]{1,39}$");

    /** A pair whose assignability differs before and after a proposed edit. */
    public record PreviewChange(String assigner, String assigned, boolean nowAssignable) {}

    private static final Set<String> BUILT_IN_NAMES = Set.of(BuiltInRoles.SUPER_ADMIN, BuiltInRoles.ACCOUNT_MANAGER,
            BuiltInRoles.CUSTOMER_ADMIN, BuiltInRoles.WORKSPACE_MANAGER,
            BuiltInRoles.TEAM_LEAD, BuiltInRoles.AGENT);

    private final RoleRepository roles;
    private final RoleChangeRepository changes;
    private final UserRepository users;
    private final RoleCache cache;
    private final AuthorityCatalog catalog;
    private final AssignmentPolicy policy;

    public RoleService(RoleRepository roles, RoleChangeRepository changes, UserRepository users, RoleCache cache,
            AuthorityCatalog catalog, AssignmentPolicy policy) {
        this.roles = roles;
        this.changes = changes;
        this.users = users;
        this.cache = cache;
        this.catalog = catalog;
        this.policy = policy;
    }

    public List<Role> list() {
        return roles.findAll().stream()
                .sorted(Comparator.comparing(Role::getLevel).thenComparing(Role::getName)).toList();
    }

    public long userCount(Role role) {
        return users.countByRoleIdOrWorkspaceRolesRoleId(role.getId(), role.getId());
    }

    public Role create(String name, String description, RoleLevel level, Set<String> authorities, String actor) {
        validateName(name);
        requireNameFree(name);
        validateAuthorities(level, authorities);
        Role role = new Role();
        role.setName(name);
        role.setDescription(description);
        role.setLevel(level);
        role.setAuthorities(new TreeSet<>(authorities));
        role = roles.save(role);
        cache.evictAll();
        audit(role, actor, RoleChange.Action.CREATED, Set.of(), authorities, null, description);
        return role;
    }

    public Role update(String id, String name, String description, Set<String> authorities, String actor) {
        Role role = editable(id);
        if (role.isBuiltIn()) {
            if (name != null && !name.equals(role.getName())) {
                throw new ValidationException("name", "cannot be changed on a built-in role");
            }
        } else if (name != null && !name.equals(role.getName())) {
            validateName(name);
            requireNameFree(name);
            role.setName(name);
        }
        validateAuthorities(role.getLevel(), authorities);
        Set<String> before = Set.copyOf(role.getAuthorities());
        String descriptionBefore = role.getDescription();
        role.setDescription(description);
        role.setAuthorities(new TreeSet<>(authorities));
        role = roles.save(role);
        cache.evictAll();
        audit(role, actor, RoleChange.Action.UPDATED, before, authorities, descriptionBefore, description);
        return role;
    }

    public void delete(String id, String actor) {
        Role role = editable(id);
        if (role.isBuiltIn()) throw new ForbiddenException("built-in roles cannot be deleted");
        if (userCount(role) > 0) throw new RoleInUseException("role is still held by users");
        roles.delete(role);
        cache.evictAll();
        audit(role, actor, RoleChange.Action.DELETED, role.getAuthorities(), Set.of(), role.getDescription(), null);
    }

    public List<RoleChange> changesFor(String id) {
        return changes.findByRoleIdOrderByAtDescIdDesc(id);
    }

    /** Pairs whose "may give" outcome flips if the role held {@code proposed}; nothing is saved. */
    public List<PreviewChange> preview(String id, Set<String> proposed) {
        Role role = editable(id);
        validateAuthorities(role.getLevel(), proposed);
        List<Role> current = roles.findAll().stream()
                .filter(r -> !BuiltInRoles.SUPER_ADMIN.equals(r.getName())).toList();
        List<Role> after = current.stream().map(r -> r.getId().equals(id) ? withAuthorities(r, proposed) : r).toList();
        List<PreviewChange> out = new ArrayList<>();
        for (RoleLevel level : new RoleLevel[] {RoleLevel.BRAND, RoleLevel.PLATFORM}) {
            String manage = level == RoleLevel.BRAND ? Authority.USER_MANAGE.code() : Authority.PLATFORM_USER_MANAGE.code();
            for (int i = 0; i < current.size(); i++) {
                if (!mayAssignAt(current.get(i).getLevel(), level)) continue;
                for (int j = 0; j < current.size(); j++) {
                    if (i == j || current.get(j).getLevel() != level) continue;
                    boolean was = gives(current.get(i), current.get(j), manage);
                    boolean will = gives(after.get(i), after.get(j), manage);
                    if (was != will) {
                        out.add(new PreviewChange(current.get(i).getName(), current.get(j).getName(), will));
                    }
                }
            }
        }
        out.sort(Comparator.comparing(PreviewChange::assigner).thenComparing(PreviewChange::assigned));
        return out;
    }

    /** BRAND roles are given by BRAND roles and by COMPANY roles (the Customer Admin); PLATFORM by PLATFORM. */
    private static boolean mayAssignAt(RoleLevel assigner, RoleLevel assigned) {
        return assigner == assigned || (assigned == RoleLevel.BRAND && assigner == RoleLevel.COMPANY);
    }

    private boolean gives(Role assigner, Role assigned, String manage) {
        Set<String> outer = policy.effective(assigner);
        return outer.contains(manage) && AssignmentPolicy.strictSubset(policy.effective(assigned), outer);
    }

    private static Role withAuthorities(Role r, Set<String> authorities) {
        Role copy = new Role();
        copy.setId(r.getId());
        copy.setName(r.getName());
        copy.setLevel(r.getLevel());
        copy.setAuthorities(authorities);
        return copy;
    }

    /** The role, 404 if absent, 403 if it is the Super Admin's. */
    private Role editable(String id) {
        Role role = roles.findById(id).orElseThrow(() -> new NotFoundException("no such role"));
        if (BuiltInRoles.SUPER_ADMIN.equals(role.getName())) {
            throw new ForbiddenException("the Super Admin role cannot be changed");
        }
        return role;
    }

    private void validateName(String name) {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new ValidationException("name", "must be 2-40 characters: capital letters, digits and _, starting with a letter");
        }
    }

    private void requireNameFree(String name) {
        if (BUILT_IN_NAMES.contains(name) || roles.existsByName(name)) {
            throw new DuplicateException("name", "a role with this name already exists");
        }
    }

    private void validateAuthorities(RoleLevel level, Collection<String> authorities) {
        List<String> unknown = new ArrayList<>();
        List<String> misplaced = new ArrayList<>();
        for (String code : authorities) {
            var authorityLevel = catalog.levelOf(code);
            if (authorityLevel.isEmpty()) unknown.add(code);
            else if (!level.mayHold(authorityLevel.get())) misplaced.add(code);
        }
        Map<String, String> problems = new LinkedHashMap<>();
        if (!unknown.isEmpty()) problems.put("authorities", "unknown: " + String.join(", ", unknown));
        else if (!misplaced.isEmpty()) {
            problems.put("authorities", "not allowed at level " + level + ": " + String.join(", ", misplaced));
        }
        if (!problems.isEmpty()) throw new ValidationException(problems);
    }

    private void audit(Role role, String actor, RoleChange.Action action, Collection<String> before,
            Collection<String> after, String descriptionBefore, String descriptionAfter) {
        RoleChange change = new RoleChange();
        change.setRoleId(role.getId());
        change.setRoleName(role.getName());
        change.setChangedBy(actor);
        change.setAt(Instant.now());
        change.setAction(action);
        change.setAdded(after.stream().filter(c -> !before.contains(c)).sorted().toList());
        change.setRemoved(before.stream().filter(c -> !after.contains(c)).sorted().toList());
        change.setDescriptionBefore(descriptionBefore);
        change.setDescriptionAfter(descriptionAfter);
        changes.save(change);
    }
}
