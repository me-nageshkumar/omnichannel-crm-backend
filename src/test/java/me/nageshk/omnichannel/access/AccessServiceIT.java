package me.nageshk.omnichannel.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import me.nageshk.omnichannel.api.NotFoundException;
import me.nageshk.omnichannel.auth.AuthenticatedUser;
import me.nageshk.omnichannel.support.TestUsers;
import me.nageshk.omnichannel.tenant.Customer;
import me.nageshk.omnichannel.tenant.Workspace;
import me.nageshk.omnichannel.user.WorkspaceRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

@SpringBootTest
class AccessServiceIT {

    @Autowired AccessService access;
    @Autowired TestUsers testUsers;
    @Autowired RoleRepository roles;
    @Autowired RoleCache roleCache;

    Customer acme;
    Workspace w1;
    Workspace w2;

    @BeforeEach
    void seed() {
        testUsers.deleteAll();
        acme = testUsers.customer("Acme");
        w1 = testUsers.workspace(acme.getId(), "Brand A");
        w2 = testUsers.workspace(acme.getId(), "Brand B");
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private AuthenticatedUser customerUser(String username, String companyRole, List<WorkspaceRole> workspaceRoles) {
        return access.load(testUsers.createInCustomer(acme.getId(), username, companyRole, workspaceRoles));
    }

    private AuthenticatedUser workspaceManagerInW1() {
        return customerUser("wm", null,
                List.of(new WorkspaceRole(w1.getId(), testUsers.roleId(BuiltInRoles.WORKSPACE_MANAGER))));
    }

    private static void signIn(AuthenticatedUser user) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
    }

    @Test
    void customerAdminHasBrandAuthoritiesInEveryWorkspaceOfItsCustomer() {
        AuthenticatedUser admin = customerUser("admin", BuiltInRoles.CUSTOMER_ADMIN, List.of());

        assertThat(admin.hasAuthority("workspace:create")).isTrue();
        assertThat(admin.hasAuthority("user:manage")).as("BRAND codes are never granted globally").isFalse();
        for (Workspace w : List.of(w1, w2)) {
            assertThat(access.inWorkspace(admin, w.getId()))
                    .contains("user:manage", "user:view", "team:manage", "workspace:settings", "workspace:create");
        }
        assertThat(access.workspacesOf(admin)).extracting(Workspace::getId).containsExactly(w1.getId(), w2.getId());
    }

    @Test
    void workspaceRoleGivesThatRolesAuthoritiesOnlyThere() {
        AuthenticatedUser wm = workspaceManagerInW1();

        assertThat(access.inWorkspace(wm, w1.getId())).contains("user:manage").doesNotContain("workspace:create");
        assertThatThrownBy(() -> access.inWorkspace(wm, w2.getId())).isInstanceOf(NotFoundException.class);
        assertThat(access.workspacesOf(wm)).extracting(Workspace::getId).containsExactly(w1.getId());
    }

    @Test
    void hasReadsThePrincipalAndSeparatesNoAccessFromNoAuthority() {
        signIn(customerUser("lead", null,
                List.of(new WorkspaceRole(w1.getId(), testUsers.roleId(BuiltInRoles.TEAM_LEAD)))));

        assertThat(access.has(w1.getId(), "user:view")).isTrue();
        assertThat(access.has(w1.getId(), "user:manage")).isFalse();
        assertThatThrownBy(() -> access.has(w2.getId(), "user:view")).isInstanceOf(NotFoundException.class);
    }

    @Test
    void otherCustomersWorkspaceIsNotFound() {
        Customer other = testUsers.customer("Other");
        Workspace theirs = testUsers.workspace(other.getId(), "Theirs");
        AuthenticatedUser admin = customerUser("admin", BuiltInRoles.CUSTOMER_ADMIN, List.of());

        assertThatThrownBy(() -> access.inWorkspace(admin, theirs.getId())).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> access.inWorkspace(admin, "no-such-workspace")).isInstanceOf(NotFoundException.class);
    }

    @Test
    void platformStaffHaveNoBrandAccess() {
        AuthenticatedUser superAdmin = access.load(testUsers.create("root", "pw", BuiltInRoles.SUPER_ADMIN, null));
        AuthenticatedUser manager = access.load(testUsers.create("am", "pw", BuiltInRoles.ACCOUNT_MANAGER, null));

        assertThat(superAdmin.superAdmin()).isTrue();
        assertThat(superAdmin.hasAuthority("platform-user:manage")).isTrue();
        assertThat(superAdmin.hasAuthority("whatsapp:bridge")).isTrue();
        assertThat(superAdmin.hasAuthority("SUPER_ADMIN")).isTrue();
        assertThat(superAdmin.hasAuthority("user:manage")).isFalse();
        assertThat(manager.hasAuthority("customer:create")).isTrue();
        assertThat(manager.hasAuthority("platform-user:manage")).isFalse();

        for (AuthenticatedUser staff : List.of(superAdmin, manager)) {
            assertThatThrownBy(() -> access.inWorkspace(staff, w1.getId())).isInstanceOf(NotFoundException.class);
            assertThat(access.workspacesOf(staff)).isEmpty();
        }
    }

    @Test
    void roleEditAppliesAfterEvict() {
        AuthenticatedUser wm = workspaceManagerInW1();
        assertThat(access.inWorkspace(wm, w1.getId())).contains("user:manage");

        Role role = roles.findByName(BuiltInRoles.WORKSPACE_MANAGER).orElseThrow();
        role.getAuthorities().remove("user:manage");
        roles.save(role);
        roleCache.evictAll();

        assertThat(access.inWorkspace(wm, w1.getId())).doesNotContain("user:manage").contains("user:view");
    }
}
