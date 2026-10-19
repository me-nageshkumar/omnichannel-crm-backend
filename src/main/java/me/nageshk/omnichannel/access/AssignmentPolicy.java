package me.nageshk.omnichannel.access;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Who may give which role to whom: the strict-subset rule. Pure and stateless; callers resolve the
 * acting user and the target into {@link Actor} and {@link Target} first.
 *
 * <ol>
 *   <li>The actor holds the managing authority for the scope: {@code user:manage} in the workspace
 *       for a BRAND role, {@code platform-user:manage} for a PLATFORM role, {@code customer:manage}
 *       or {@code customer:create} (platform staff) for a COMPANY role.
 *   <li>The role's authorities are a strict subset of the actor's effective set in that scope
 *       (COMPANY roles given by platform staff excepted: different levels, nothing to compare).
 *   <li>For an existing user, the actor passes rule 2 against the user's role in the workspace
 *       (one-workspace form) or in every workspace where the user has a role, with no COMPANY role
 *       unless the actor is platform staff with {@code customer:manage} (whole-login form).
 *   <li>The actor belongs to the user's customer (that platform-staff exception aside).
 * </ol>
 *
 * The Super Admin passes every check, but the {@code SUPER_ADMIN} role is never given to anyone.
 * Role codes no longer in the catalogue (or not at a level the role may hold) grant nothing, so
 * they are ignored when comparing.
 */
@Component
public class AssignmentPolicy {

    private static final String USER_MANAGE = Authority.USER_MANAGE.code();
    private static final String PLATFORM_USER_MANAGE = Authority.PLATFORM_USER_MANAGE.code();
    private static final String CUSTOMER_MANAGE = Authority.CUSTOMER_MANAGE.code();
    private static final String CUSTOMER_CREATE = Authority.CUSTOMER_CREATE.code();
    private static final String COMPANY_USER_MANAGE = Authority.COMPANY_USER_MANAGE.code();

    /**
     * The acting user, resolved.
     *
     * @param customerId null for platform users
     * @param platformOrCompany PLATFORM codes for a platform user, COMPANY codes for a customer user
     * @param perWorkspace effective set in every workspace the actor can access, by workspace id
     */
    public record Actor(boolean superAdmin, String customerId, Set<String> platformOrCompany,
            Map<String, Set<String>> perWorkspace) {
        public Actor {
            platformOrCompany = platformOrCompany == null ? Set.of() : Set.copyOf(platformOrCompany);
            perWorkspace = perWorkspace == null ? Map.of() : copy(perWorkspace);
        }

        private static Map<String, Set<String>> copy(Map<String, Set<String>> source) {
            Map<String, Set<String>> out = new HashMap<>();
            source.forEach((w, codes) -> out.put(w, codes == null ? Set.of() : Set.copyOf(codes)));
            return Map.copyOf(out);
        }
    }

    /**
     * The user being acted on, resolved.
     *
     * @param companyRole the user's COMPANY role, or null
     * @param platformRole a platform user's PLATFORM role, or null
     * @param workspaceRoles the user's BRAND role in each workspace, by workspace id
     */
    public record Target(String customerId, Role companyRole, Role platformRole, Map<String, Role> workspaceRoles) {
        public Target {
            workspaceRoles = workspaceRoles == null ? Map.of() : Map.copyOf(workspaceRoles);
        }
    }

    /** {@code inner} ⊆ {@code outer} and {@code inner} ≠ {@code outer}. */
    public static boolean strictSubset(Set<String> inner, Set<String> outer) {
        return outer.containsAll(inner) && !inner.containsAll(outer);
    }

    /** Rules 1 and 2 for a BRAND role in one workspace. */
    public boolean canGiveBrandRole(Actor a, String workspaceId, Role role) {
        if (!isAt(role, RoleLevel.BRAND)) return false;
        if (a.superAdmin()) return true;
        Set<String> actorSet = a.perWorkspace().get(workspaceId);
        return actorSet != null && actorSet.contains(USER_MANAGE) && strictSubset(effective(role), actorSet);
    }

    /** Rules 1 and 2 for a PLATFORM role. {@code SUPER_ADMIN} is never given, even by the Super Admin. */
    public boolean canGivePlatformRole(Actor a, Role role) {
        if (!isAt(role, RoleLevel.PLATFORM) || BuiltInRoles.SUPER_ADMIN.equals(role.getName())) return false;
        if (a.superAdmin()) return true;
        return isPlatformStaff(a)
                && a.platformOrCompany().contains(PLATFORM_USER_MANAGE)
                && strictSubset(effective(role), a.platformOrCompany());
    }

    /** A COMPANY role: platform staff with {@code customer:manage} or {@code customer:create}; no subset check. */
    public boolean canGiveCompanyRole(Actor a, Role role) {
        if (!isAt(role, RoleLevel.COMPANY)) return false;
        if (a.superAdmin()) return true;
        return isPlatformStaff(a)
                && (a.platformOrCompany().contains(CUSTOMER_MANAGE) || a.platformOrCompany().contains(CUSTOMER_CREATE));
    }

    /**
     * Rule 3, one-workspace form (change or remove the target's role in the workspace), with rule 4.
     * The target's authorities there include its COMPANY role's, so a Customer Admin is only ever
     * changeable by someone holding strictly more.
     */
    public boolean canChangeInWorkspace(Actor a, Target t, String workspaceId) {
        if (a.superAdmin()) return true;
        if (!sameCustomer(a, t)) return false;
        Set<String> targetSet = new HashSet<>();
        if (t.companyRole() != null) targetSet.addAll(effective(t.companyRole()));
        Role inWorkspace = t.workspaceRoles().get(workspaceId);
        if (inWorkspace != null) targetSet.addAll(effective(inWorkspace));
        return managesIn(a, workspaceId, targetSet);
    }

    /**
     * Rule 3, whole-login form (reset password, edit details, deactivate).
     *
     * <ul>
     *   <li>Platform user: the actor is platform staff with {@code platform-user:manage} and the
     *       target's PLATFORM role is a strict subset of the actor's set. Nobody manages the Super
     *       Admin's login, not even the Super Admin.
     *   <li>Customer user with a COMPANY role: only platform staff with {@code customer:manage}.
     *   <li>Other customer users, same customer only: the actor manages the target in every
     *       workspace where it has a role; a target with no role at all is left to whoever holds
     *       {@code company-user:manage} (the Customer Admin).
     * </ul>
     */
    public boolean canManageLogin(Actor a, Target t) {
        if (t.customerId() == null) return canManagePlatformLogin(a, t);
        if (a.superAdmin()) return true;
        if (t.companyRole() != null) {
            if (!isPlatformStaff(a) || !a.platformOrCompany().contains(CUSTOMER_MANAGE)) return false;
        } else {
            if (!sameCustomer(a, t)) return false;
            if (t.workspaceRoles().isEmpty()) return a.platformOrCompany().contains(COMPANY_USER_MANAGE);
        }
        return t.workspaceRoles().entrySet().stream()
                .allMatch(e -> managesIn(a, e.getKey(), effective(e.getValue())));
    }

    /**
     * {@link #canManageLogin} plus, for a customer user, {@code company-user:manage} (a customer
     * actor) or, for a target with a COMPANY role, {@code customer:manage} (platform staff). For a
     * platform user, the same rule as {@link #canManageLogin}.
     */
    public boolean canDeactivateLogin(Actor a, Target t) {
        if (t.customerId() == null) return canManagePlatformLogin(a, t);
        if (a.superAdmin()) return true;
        if (!canManageLogin(a, t)) return false;
        boolean companyUserManage = !isPlatformStaff(a) && a.platformOrCompany().contains(COMPANY_USER_MANAGE);
        boolean customerManage = t.companyRole() != null
                && isPlatformStaff(a) && a.platformOrCompany().contains(CUSTOMER_MANAGE);
        return companyUserManage || customerManage;
    }

    private static boolean canManagePlatformLogin(Actor a, Target t) {
        Role role = t.platformRole();
        if (role != null && BuiltInRoles.SUPER_ADMIN.equals(role.getName())) return false;
        if (a.superAdmin()) return true;
        Set<String> targetSet = isAt(role, RoleLevel.PLATFORM) ? effective(role) : Set.of();
        return isPlatformStaff(a)
                && a.platformOrCompany().contains(PLATFORM_USER_MANAGE)
                && strictSubset(targetSet, a.platformOrCompany());
    }

    public List<Role> assignableBrandRoles(Actor a, String workspaceId, Collection<Role> all) {
        return all.stream().filter(r -> canGiveBrandRole(a, workspaceId, r)).toList();
    }

    public List<Role> assignablePlatformRoles(Actor a, Collection<Role> all) {
        return all.stream().filter(r -> canGivePlatformRole(a, r)).toList();
    }

    public List<Role> assignableCompanyRoles(Actor a, Collection<Role> all) {
        return all.stream().filter(r -> canGiveCompanyRole(a, r)).toList();
    }

    /** {@code user:manage} in the workspace and the target's set there strictly below the actor's. */
    private static boolean managesIn(Actor a, String workspaceId, Set<String> targetSet) {
        Set<String> actorSet = a.perWorkspace().get(workspaceId);
        return actorSet != null && actorSet.contains(USER_MANAGE) && strictSubset(targetSet, actorSet);
    }

    private static boolean sameCustomer(Actor a, Target t) {
        return a.customerId() != null && a.customerId().equals(t.customerId());
    }

    private static boolean isPlatformStaff(Actor a) {
        return a.customerId() == null;
    }

    private static boolean isAt(Role role, RoleLevel level) {
        return role != null && role.getLevel() == level;
    }

    /** The role's catalogue codes at levels it may hold; removed or misplaced codes grant nothing. */
    private static Set<String> effective(Role role) {
        if (role.getLevel() == null || role.getAuthorities() == null) return Set.of();
        return role.getAuthorities().stream()
                .filter(code -> Authority.fromCode(code).filter(x -> role.getLevel().mayHold(x.level())).isPresent())
                .collect(Collectors.toSet());
    }
}
