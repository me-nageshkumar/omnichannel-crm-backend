package me.nageshk.omnichannel.security.access;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;
import me.nageshk.omnichannel.db.access.Role;
import me.nageshk.omnichannel.db.access.RoleLevel;
import me.nageshk.omnichannel.security.access.AssignmentPolicy.Actor;
import me.nageshk.omnichannel.security.access.AssignmentPolicy.Target;
import org.junit.jupiter.api.Test;

/** Role comparisons for a customer whose plan holds back some authorities. */
class AssignmentPolicyCeilingTest {

    private static final String ACME = "acme";
    private static final String WS = "ws";
    private static final Set<String> ADMIN_HERE =
            Set.of("user:manage", "user:view", "inbox:view", "workspace:settings");

    private final AssignmentPolicy policy = new AssignmentPolicy(AuthorityLevels.BUILT_IN);
    private final Role manager = role("MANAGER", Set.of("user:manage", "user:view", "inbox:view", "crm:view", "crm:edit"));

    @Test
    void aRoleHoldingAuthoritiesOutsideThePlanCanStillBeGiven() {
        Actor admin = new Actor(false, ACME, Set.of(), Map.of(WS, ADMIN_HERE), ADMIN_HERE);
        assertThat(policy.canGiveBrandRole(admin, WS, manager)).isTrue();
        assertThat(policy.assignableBrandRoles(admin, WS, java.util.List.of(manager))).containsExactly(manager);
    }

    @Test
    void withoutACeilingTheSameRoleIsRefusedAsToday() {
        Actor admin = new Actor(false, ACME, Set.of(), Map.of(WS, ADMIN_HERE));
        assertThat(admin.ceiling()).isNull();
        assertThat(policy.canGiveBrandRole(admin, WS, manager)).isFalse();
    }

    @Test
    void aUserHoldingSuchARoleCanBeChangedAndManaged() {
        Actor admin = new Actor(false, ACME, Set.of("company-user:manage"), Map.of(WS, ADMIN_HERE), ADMIN_HERE);
        Target user = new Target(ACME, null, null, Map.of(WS, manager));
        assertThat(policy.canChangeInWorkspace(admin, user, WS)).isTrue();
        assertThat(policy.canManageLogin(admin, user)).isTrue();
    }

    @Test
    void aRoleEqualToTheActorInsideThePlanIsStillRefused() {
        Role twin = role("TWIN", Set.of("user:manage", "user:view", "inbox:view", "workspace:settings", "crm:view"));
        Actor admin = new Actor(false, ACME, Set.of(), Map.of(WS, ADMIN_HERE), ADMIN_HERE);
        assertThat(policy.canGiveBrandRole(admin, WS, twin)).isFalse();
    }

    private static Role role(String name, Set<String> authorities) {
        Role r = new Role();
        r.setId(name);
        r.setName(name);
        r.setLevel(RoleLevel.BRAND);
        r.setAuthorities(authorities);
        return r;
    }
}
