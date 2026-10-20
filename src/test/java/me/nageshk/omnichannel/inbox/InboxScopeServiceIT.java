package me.nageshk.omnichannel.inbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import me.nageshk.omnichannel.access.AccessService;
import me.nageshk.omnichannel.access.BuiltInRoles;
import me.nageshk.omnichannel.api.NotFoundException;
import me.nageshk.omnichannel.auth.AuthenticatedUser;
import me.nageshk.omnichannel.inbox.InboxScope.Level;
import me.nageshk.omnichannel.inbox.InboxScope.ListScope;
import me.nageshk.omnichannel.support.TestUsers;
import me.nageshk.omnichannel.tenant.Customer;
import me.nageshk.omnichannel.tenant.Team;
import me.nageshk.omnichannel.tenant.TeamRepository;
import me.nageshk.omnichannel.tenant.Workspace;
import me.nageshk.omnichannel.user.User;
import me.nageshk.omnichannel.user.WorkspaceRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class InboxScopeServiceIT {

    @Autowired InboxScopeService scopes;
    @Autowired AccessService access;
    @Autowired TestUsers testUsers;
    @Autowired TeamRepository teams;

    Customer acme;
    Workspace brand;
    Workspace other;

    @BeforeEach
    void setUp() {
        testUsers.deleteAll();
        acme = testUsers.customer("Acme");
        brand = testUsers.workspace(acme.getId(), "Brand A");
        other = testUsers.workspace(acme.getId(), "Brand B");
    }

    @AfterEach
    void cleanUp() {
        testUsers.deleteAll();
    }

    private AuthenticatedUser brandUser(String username, String roleName, Workspace w) {
        User u = testUsers.createInCustomer(acme.getId(), username, null,
                List.of(new WorkspaceRole(w.getId(), testUsers.roleId(roleName))));
        return access.load(u);
    }

    private Team team(Workspace w, String name, String... memberIds) {
        Team t = new Team();
        t.setCustomerId(acme.getId());
        t.setWorkspaceId(w.getId());
        t.setName(name);
        t.setMemberIds(new ArrayList<>(List.of(memberIds)));
        return teams.save(t);
    }

    @Test
    void agentGetsMine() {
        AuthenticatedUser agent = brandUser("agent", BuiltInRoles.AGENT, brand);
        team(brand, "Support", agent.userId());

        InboxScope scope = scopes.scopeOf(agent, brand.getId());

        assertThat(scope.level()).isEqualTo(Level.MINE);
        assertThat(scope.userId()).isEqualTo(agent.userId());
        assertThat(scope.teamIds()).isEmpty();
        assertThat(scopes.defaultScope(scope)).isEqualTo(ListScope.MINE);
    }

    @Test
    void teamLeadGetsTeamWithOwnMemberships() {
        AuthenticatedUser lead = brandUser("lead", BuiltInRoles.TEAM_LEAD, brand);
        Team support = team(brand, "Support", lead.userId());
        team(brand, "Sales", "someone-else");
        team(other, "Elsewhere", lead.userId());

        InboxScope scope = scopes.scopeOf(lead, brand.getId());

        assertThat(scope.level()).isEqualTo(Level.TEAM);
        assertThat(scope.teamIds()).containsExactly(support.getId());
        assertThat(scopes.defaultScope(scope)).isEqualTo(ListScope.TEAM);
    }

    @Test
    void workspaceManagerGetsAllWithEveryTeam() {
        AuthenticatedUser wm = brandUser("wm", BuiltInRoles.WORKSPACE_MANAGER, brand);
        Team support = team(brand, "Support", "x");
        Team sales = team(brand, "Sales", wm.userId());
        team(other, "Elsewhere", "x");

        InboxScope scope = scopes.scopeOf(wm, brand.getId());

        assertThat(scope.level()).isEqualTo(Level.ALL);
        assertThat(scope.teamIds()).containsExactlyInAnyOrder(support.getId(), sales.getId());
        assertThat(scopes.defaultScope(scope)).isEqualTo(ListScope.ALL);
    }

    @Test
    void userWithoutAccessIsNotFound() {
        AuthenticatedUser stranger = brandUser("stranger", BuiltInRoles.AGENT, other);

        assertThatThrownBy(() -> scopes.scopeOf(stranger, brand.getId())).isInstanceOf(NotFoundException.class);
    }
}
