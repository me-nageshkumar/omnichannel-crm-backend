package me.nageshk.omnichannel.access;

import static me.nageshk.omnichannel.access.BuiltInRoles.*;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import me.nageshk.omnichannel.access.AssignmentPolicy.Actor;
import me.nageshk.omnichannel.access.AssignmentPolicy.Target;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AssignmentPolicyTest {

    private static final String ACME = "acme";
    private static final String OTHER = "other-customer";
    private static final String A = "ws-a";
    private static final String B = "ws-b";

    private final AssignmentPolicy policy = new AssignmentPolicy(AuthorityLevels.BUILT_IN);

    private Role superAdmin, accountManager, device, customerAdmin, workspaceManager, teamLead, agent;
    private List<Role> all;

    @BeforeEach
    void seedDefaults() {
        superAdmin = role(SUPER_ADMIN, RoleLevel.PLATFORM, Set.of());
        accountManager = defaultRole(ACCOUNT_MANAGER);
        device = defaultRole(WHATSAPP_DEVICE);
        customerAdmin = defaultRole(CUSTOMER_ADMIN);
        workspaceManager = defaultRole(WORKSPACE_MANAGER);
        teamLead = defaultRole(TEAM_LEAD);
        agent = defaultRole(AGENT);
        all = List.of(superAdmin, accountManager, device, customerAdmin, workspaceManager, teamLead, agent);
    }

    // --- giving BRAND roles -------------------------------------------------------------------

    @Test
    void customerAdminGivesWorkspaceManagerTeamLeadAgent() {
        Actor ca = customerAdminOf(ACME, A, B);
        assertThat(policy.assignableBrandRoles(ca, A, all))
                .containsExactlyInAnyOrder(workspaceManager, teamLead, agent);
        assertThat(policy.canGiveBrandRole(ca, B, workspaceManager)).isTrue();
        assertThat(policy.canGiveBrandRole(ca, "ws-elsewhere", agent)).isFalse();
    }

    @Test
    void workspaceManagerGivesTeamLeadAndAgentButNotWorkspaceManager() {
        Actor wm = brandUser(ACME, Map.of(A, workspaceManager));
        assertThat(policy.assignableBrandRoles(wm, A, all)).containsExactlyInAnyOrder(teamLead, agent);
        assertThat(policy.canGiveBrandRole(wm, A, workspaceManager)).isFalse();
        assertThat(policy.canGiveBrandRole(wm, B, agent)).isFalse();
    }

    @Test
    void teamLeadGivesNothing() {
        Actor tl = brandUser(ACME, Map.of(A, teamLead));
        assertThat(policy.assignableBrandRoles(tl, A, all)).isEmpty();
        assertThat(policy.canGiveBrandRole(tl, A, agent)).isFalse();
    }

    @Test
    void teamLeadWithUserManageGivesAgentButNotTeamLead() {
        teamLead.setAuthorities(Set.of("user:view", "user:manage"));
        Actor tl = brandUser(ACME, Map.of(A, teamLead));
        assertThat(policy.assignableBrandRoles(tl, A, all)).containsExactly(agent);
    }

    @Test
    void emptyRoleIsAssignableByAnyManager() {
        assertThat(agent.getAuthorities()).isEmpty();
        assertThat(policy.canGiveBrandRole(customerAdminOf(ACME, A), A, agent)).isTrue();
        assertThat(policy.canGiveBrandRole(brandUser(ACME, Map.of(A, workspaceManager)), A, agent)).isTrue();
        Role managerOnly = role("MANAGER_ONLY", RoleLevel.BRAND, Set.of("user:manage"));
        assertThat(policy.canGiveBrandRole(brandUser(ACME, Map.of(A, managerOnly)), A, agent)).isTrue();
    }

    @Test
    void brandRoleIsNeverGivenAsAnotherLevel() {
        Actor ca = customerAdminOf(ACME, A);
        assertThat(policy.canGiveBrandRole(ca, A, accountManager)).isFalse();
        assertThat(policy.canGiveBrandRole(ca, A, customerAdmin)).isFalse();
        assertThat(policy.canGiveBrandRole(ca, A, null)).isFalse();
    }

    @Test
    void storedCodesRemovedFromTheCatalogueDoNotBlockAssignment() {
        Role legacy = role("LEGACY", RoleLevel.BRAND, Set.of("user:view", "inbox:removed"));
        assertThat(policy.canGiveBrandRole(brandUser(ACME, Map.of(A, workspaceManager)), A, legacy)).isTrue();
    }

    // --- one workspace and whole login --------------------------------------------------------

    @Test
    void workspaceManagerCannotResetAnotherWorkspaceManager() {
        Actor wm = brandUser(ACME, Map.of(A, workspaceManager));
        Target otherWm = target(ACME, null, Map.of(A, workspaceManager));
        assertThat(policy.canChangeInWorkspace(wm, otherWm, A)).isFalse();
        assertThat(policy.canManageLogin(wm, otherWm)).isFalse();
    }

    @Test
    void workspaceManagerChangesAnAgentInItsWorkspaceOnly() {
        Actor wm = brandUser(ACME, Map.of(A, workspaceManager));
        Target agentInAandB = target(ACME, null, Map.of(A, agent, B, teamLead));
        assertThat(policy.canChangeInWorkspace(wm, agentInAandB, A)).isTrue();
        assertThat(policy.canChangeInWorkspace(wm, agentInAandB, B)).isFalse();
        assertThat(policy.canChangeInWorkspace(wm, target(ACME, null, Map.of(B, agent)), A)).isTrue();
    }

    @Test
    void workspaceManagerOfAOnlyCannotManageLoginOfAgentAlsoInB() {
        Actor wm = brandUser(ACME, Map.of(A, workspaceManager));
        Target agentInAandB = target(ACME, null, Map.of(A, agent, B, agent));
        assertThat(policy.canManageLogin(wm, agentInAandB)).isFalse();
    }

    @Test
    void workspaceManagerOfBothManagesThatAgent() {
        Actor wm = brandUser(ACME, Map.of(A, workspaceManager, B, workspaceManager));
        Target agentInAandB = target(ACME, null, Map.of(A, agent, B, agent));
        assertThat(policy.canManageLogin(wm, agentInAandB)).isTrue();
    }

    @Test
    void workspaceManagerCannotManageCustomerAdmin() {
        Actor wm = brandUser(ACME, Map.of(A, workspaceManager));
        Target ca = target(ACME, customerAdmin, Map.of());
        assertThat(policy.canManageLogin(wm, ca)).isFalse();
        assertThat(policy.canChangeInWorkspace(wm, ca, A)).isFalse();
    }

    @Test
    void accountManagerManagesCustomerAdminLogin() {
        Actor am = platformUser(accountManager);
        assertThat(policy.canManageLogin(am, target(ACME, customerAdmin, Map.of()))).isTrue();
        assertThat(policy.canManageLogin(am, target(OTHER, customerAdmin, Map.of()))).isTrue();
        assertThat(policy.canManageLogin(am, target(ACME, null, Map.of(A, agent)))).isFalse();
        assertThat(policy.canManageLogin(am, target(ACME, null, Map.of()))).isFalse();
        assertThat(policy.canChangeInWorkspace(am, target(ACME, null, Map.of(A, agent)), A)).isFalse();
    }

    @Test
    void otherCustomerIsNeverManageable() {
        Actor ca = customerAdminOf(ACME, A);
        Actor wm = brandUser(ACME, Map.of(A, workspaceManager));
        Target foreignAgent = target(OTHER, null, Map.of(A, agent));
        for (Actor actor : List.of(ca, wm)) {
            assertThat(policy.canManageLogin(actor, foreignAgent)).isFalse();
            assertThat(policy.canChangeInWorkspace(actor, foreignAgent, A)).isFalse();
            assertThat(policy.canDisableLogin(actor, foreignAgent)).isFalse();
        }
        assertThat(policy.canManageLogin(ca, target(null, null, Map.of()))).isFalse();
    }

    // --- PLATFORM and COMPANY roles -----------------------------------------------------------

    @Test
    void customPlatformRoleWithPlatformUserManageCannotGiveItsEqual() {
        Role staffLead = role("STAFF_LEAD", RoleLevel.PLATFORM, Set.of("platform-user:manage", "customer:view"));
        Role viewer = role("VIEWER", RoleLevel.PLATFORM, Set.of("customer:view"));
        Actor lead = platformUser(staffLead);
        assertThat(policy.canGivePlatformRole(lead, staffLead)).isFalse();
        assertThat(policy.canGivePlatformRole(lead, viewer)).isTrue();
        assertThat(policy.canGivePlatformRole(lead, accountManager)).isFalse();
        assertThat(policy.canGivePlatformRole(lead, superAdmin)).isFalse();
        assertThat(policy.canGivePlatformRole(platformUser(accountManager), viewer)).isFalse();
        assertThat(policy.assignablePlatformRoles(lead, List.of(staffLead, viewer, accountManager, agent)))
                .containsExactly(viewer);
    }

    @Test
    void superAdminCanGiveAnyPlatformRoleButSuperAdmin() {
        Actor sa = superAdminActor();
        assertThat(policy.assignablePlatformRoles(sa, all)).containsExactlyInAnyOrder(accountManager, device);
        assertThat(policy.canGivePlatformRole(sa, superAdmin)).isFalse();
        assertThat(policy.canGiveCompanyRole(sa, customerAdmin)).isTrue();
        assertThat(policy.canGiveBrandRole(sa, A, workspaceManager)).isTrue();
        Target ca = target(ACME, customerAdmin, Map.of());
        assertThat(policy.canManageLogin(sa, ca)).isTrue();
        assertThat(policy.canDisableLogin(sa, target(ACME, null, Map.of(A, workspaceManager)))).isTrue();
    }

    @Test
    void companyRoleOnlyFromPlatformStaff() {
        assertThat(policy.assignableCompanyRoles(platformUser(accountManager), all)).containsExactly(customerAdmin);
        Role creator = role("ONBOARDER", RoleLevel.PLATFORM, Set.of("customer:create"));
        assertThat(policy.canGiveCompanyRole(platformUser(creator), customerAdmin)).isTrue();
        assertThat(policy.canGiveCompanyRole(platformUser(device), customerAdmin)).isFalse();
        assertThat(policy.canGiveCompanyRole(customerAdminOf(ACME, A), customerAdmin)).isFalse();
        assertThat(policy.assignableCompanyRoles(customerAdminOf(ACME, A), all)).isEmpty();
        assertThat(policy.canGiveCompanyRole(platformUser(accountManager), workspaceManager)).isFalse();
    }

    // --- deactivation -------------------------------------------------------------------------

    @Test
    void customerAdminCannotDeactivateAnotherCustomerAdmin() {
        Actor ca = customerAdminOf(ACME, A);
        assertThat(policy.canDisableLogin(ca, target(ACME, customerAdmin, Map.of()))).isFalse();
        assertThat(policy.canDisableLogin(ca, target(ACME, null, Map.of(A, workspaceManager)))).isTrue();
    }

    @Test
    void workspaceManagerCannotDeactivateEvenAnAgentItManages() {
        Actor wm = brandUser(ACME, Map.of(A, workspaceManager));
        Target agentInA = target(ACME, null, Map.of(A, agent));
        assertThat(policy.canManageLogin(wm, agentInA)).isTrue();
        assertThat(policy.canDisableLogin(wm, agentInA)).isFalse();
    }

    @Test
    void accountManagerCanDeactivateCustomerAdmin() {
        Actor am = platformUser(accountManager);
        assertThat(policy.canDisableLogin(am, target(ACME, customerAdmin, Map.of()))).isTrue();
    }

    @Test
    void platformStaffMayDisableAnyCustomerUser() {
        Actor am = platformUser(accountManager);
        assertThat(policy.canDisableLogin(am, target(ACME, null, Map.of(A, agent)))).isTrue();
        assertThat(policy.canDisableLogin(am, target(ACME, null, Map.of()))).isTrue();
    }

    // --- roleless customer users ----------------------------------------------------------------

    @Test
    void agentCannotManageRolelessLogin() {
        Actor agentActor = brandUser(ACME, Map.of(A, agent));
        Target roleless = target(ACME, null, Map.of());
        assertThat(policy.canManageLogin(agentActor, roleless)).isFalse();
        assertThat(policy.canDisableLogin(agentActor, roleless)).isFalse();
    }

    @Test
    void workspaceManagerCannotManageRolelessLogin() {
        Actor wm = brandUser(ACME, Map.of(A, workspaceManager, B, workspaceManager));
        assertThat(policy.canManageLogin(wm, target(ACME, null, Map.of()))).isFalse();
    }

    @Test
    void customerAdminManagesRolelessLogin() {
        Actor ca = customerAdminOf(ACME, A);
        Target roleless = target(ACME, null, Map.of());
        assertThat(policy.canManageLogin(ca, roleless)).isTrue();
        assertThat(policy.canDisableLogin(ca, roleless)).isTrue();
        assertThat(policy.canManageLogin(customerAdminOf(OTHER, A), roleless)).isFalse();
    }

    // --- platform users -----------------------------------------------------------------------

    @Test
    void staffLeadWithPlatformUserManageManagesAccountManager() {
        Set<String> codes = new HashSet<>(accountManager.getAuthorities());
        codes.add("platform-user:manage");
        Actor lead = platformUser(role("STAFF_LEAD", RoleLevel.PLATFORM, codes));
        Target am = platformTarget(accountManager);
        assertThat(policy.canManageLogin(lead, am)).isTrue();
        assertThat(policy.canDisableLogin(lead, am)).isTrue();
        assertThat(policy.canManageLogin(lead, platformTarget(role("STAFF_LEAD", RoleLevel.PLATFORM, codes))))
                .isFalse();
        assertThat(policy.canManageLogin(lead, platformTarget(device))).isFalse();
    }

    @Test
    void accountManagerCannotManageAnotherAccountManager() {
        Actor am = platformUser(accountManager);
        assertThat(policy.canManageLogin(am, platformTarget(accountManager))).isFalse();
        assertThat(policy.canDisableLogin(am, platformTarget(accountManager))).isFalse();
        assertThat(policy.canManageLogin(am, platformTarget(role("EMPTY", RoleLevel.PLATFORM, Set.of()))))
                .isFalse();
    }

    @Test
    void nobodyManagesSuperAdminLogin() {
        Target sa = platformTarget(superAdmin);
        Set<String> everything = new HashSet<>(superAdminActor().platformOrCompany());
        Actor lead = platformUser(role("STAFF_LEAD", RoleLevel.PLATFORM, everything));
        for (Actor actor : List.of(superAdminActor(), lead, platformUser(accountManager))) {
            assertThat(policy.canManageLogin(actor, sa)).isFalse();
            assertThat(policy.canDisableLogin(actor, sa)).isFalse();
        }
        assertThat(policy.canManageLogin(superAdminActor(), platformTarget(accountManager))).isTrue();
        assertThat(policy.canDisableLogin(superAdminActor(), platformTarget(device))).isTrue();
    }

    @Test
    void customerAdminCannotManagePlatformUser() {
        Actor ca = customerAdminOf(ACME, A);
        Target empty = platformTarget(role("EMPTY", RoleLevel.PLATFORM, Set.of()));
        for (Target t : List.of(platformTarget(accountManager), platformTarget(device), empty)) {
            assertThat(policy.canManageLogin(ca, t)).isFalse();
            assertThat(policy.canDisableLogin(ca, t)).isFalse();
        }
    }

    // --- strict subset ------------------------------------------------------------------------

    @Test
    void strictSubsetExcludesEqualAndIncomparableSets() {
        assertThat(AssignmentPolicy.strictSubset(Set.of(), Set.of("a"))).isTrue();
        assertThat(AssignmentPolicy.strictSubset(Set.of("a"), Set.of("a", "b"))).isTrue();
        assertThat(AssignmentPolicy.strictSubset(Set.of("a", "b"), Set.of("a", "b"))).isFalse();
        assertThat(AssignmentPolicy.strictSubset(Set.of(), Set.of())).isFalse();
        assertThat(AssignmentPolicy.strictSubset(Set.of("a", "c"), Set.of("a", "b"))).isFalse();
        assertThat(AssignmentPolicy.strictSubset(Set.of("a", "b"), Set.of("a"))).isFalse();
    }

    // --- fixtures: actors and targets from the seeded default sets ----------------------------

    private static Role defaultRole(String name) {
        Set<String> codes = Arrays.stream(Authority.values())
                .filter(a -> a.defaultRoles().contains(name))
                .map(Authority::code)
                .collect(Collectors.toSet());
        return role(name, levelOf(name), codes);
    }

    private static Role role(String name, RoleLevel level, Set<String> authorities) {
        Role r = new Role();
        r.setId("id-" + name);
        r.setName(name);
        r.setLevel(level);
        r.setAuthorities(new HashSet<>(authorities));
        r.setBuiltIn(true);
        return r;
    }

    @Test
    void effectiveUsesTheGivenLevels() {
        AssignmentPolicy custom = new AssignmentPolicy(
                code -> "x:y".equals(code) ? java.util.Optional.of(RoleLevel.BRAND) : java.util.Optional.empty());
        Role brand = role("CUSTOM", RoleLevel.BRAND, Set.of("x:y", "nope:z"));

        assertThat(custom.effective(brand)).containsExactly("x:y");
    }

    /** Codes of the role's authorities at the given levels, as AccessService computes effective sets. */
    private static Set<String> codesAt(Role role, RoleLevel... levels) {
        Set<RoleLevel> wanted = Set.of(levels);
        return role.getAuthorities().stream()
                .filter(c -> Authority.fromCode(c).filter(a -> wanted.contains(a.level())).isPresent())
                .collect(Collectors.toSet());
    }

    private Actor superAdminActor() {
        Set<String> codes = Arrays.stream(Authority.values())
                .filter(a -> a.level() == RoleLevel.PLATFORM)
                .map(Authority::code)
                .collect(Collectors.toCollection(HashSet::new));
        codes.add(SUPER_ADMIN);
        return new Actor(true, null, codes, Map.of());
    }

    private static Actor platformUser(Role role) {
        return new Actor(false, null, codesAt(role, RoleLevel.PLATFORM), Map.of());
    }

    private Actor customerAdminOf(String customerId, String... workspaceIds) {
        Map<String, Set<String>> perWorkspace = new HashMap<>();
        for (String w : workspaceIds) {
            perWorkspace.put(w, codesAt(customerAdmin, RoleLevel.COMPANY, RoleLevel.BRAND));
        }
        return new Actor(false, customerId, codesAt(customerAdmin, RoleLevel.COMPANY), perWorkspace);
    }

    private static Actor brandUser(String customerId, Map<String, Role> roles) {
        Map<String, Set<String>> perWorkspace = new HashMap<>();
        roles.forEach((w, r) -> perWorkspace.put(w, codesAt(r, RoleLevel.BRAND)));
        return new Actor(false, customerId, Set.of(), perWorkspace);
    }

    private static Target target(String customerId, Role companyRole, Map<String, Role> workspaceRoles) {
        return new Target(customerId, companyRole, null, workspaceRoles);
    }

    private static Target platformTarget(Role platformRole) {
        return new Target(null, null, platformRole, Map.of());
    }
}
