package me.nageshk.omnichannel.app.inbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import me.nageshk.omnichannel.app.support.TestUsers;
import me.nageshk.omnichannel.common.api.NotFoundException;
import me.nageshk.omnichannel.db.access.Role;
import me.nageshk.omnichannel.db.access.RoleLevel;
import me.nageshk.omnichannel.db.access.RoleRepository;
import me.nageshk.omnichannel.db.inbox.ChatStatus;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import me.nageshk.omnichannel.db.inbox.WaWebChatRepository;
import me.nageshk.omnichannel.db.phone.PhoneNumber;
import me.nageshk.omnichannel.db.phone.PhoneNumberRepository;
import me.nageshk.omnichannel.db.tenant.Customer;
import me.nageshk.omnichannel.db.tenant.Team;
import me.nageshk.omnichannel.db.tenant.TeamRepository;
import me.nageshk.omnichannel.db.tenant.Workspace;
import me.nageshk.omnichannel.db.user.User;
import me.nageshk.omnichannel.db.user.WorkspaceRole;
import me.nageshk.omnichannel.security.access.AccessService;
import me.nageshk.omnichannel.security.access.BuiltInRoles;
import me.nageshk.omnichannel.security.access.RoleCache;
import me.nageshk.omnichannel.security.auth.AuthenticatedUser;
import me.nageshk.omnichannel.service.inbox.InboxScope.Level;
import me.nageshk.omnichannel.service.inbox.InboxScope.ListScope;
import me.nageshk.omnichannel.service.inbox.InboxScope;
import me.nageshk.omnichannel.service.inbox.InboxScopeService;
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
    @Autowired RoleRepository roles;
    @Autowired RoleCache roleCache;
    @Autowired PhoneNumberRepository phoneNumbers;
    @Autowired WaWebChatRepository chats;

    Customer acme;
    Workspace brand;
    Workspace other;

    @BeforeEach
    void setUp() {
        testUsers.deleteAll();
        chats.deleteAll();
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

    private PhoneNumber number(Workspace w, Team owner) {
        PhoneNumber n = testUsers.phoneNumber(acme.getId(), w.getId(), "9199" + (System.nanoTime() % 100000000));
        n.setTeamId(owner == null ? null : owner.getId());
        return phoneNumbers.save(n);
    }

    @Test
    void agentGetsQueueWithOwnTeamsAndOwnedNumbers() {
        AuthenticatedUser agent = brandUser("agent", BuiltInRoles.AGENT, brand);
        Team support = team(brand, "Support", agent.userId());
        Team sales = team(brand, "Sales", "someone-else");
        PhoneNumber a = number(brand, support);
        number(brand, sales);
        number(brand, null);

        InboxScope scope = scopes.scopeOf(agent, brand.getId());

        assertThat(scope.level()).isEqualTo(Level.QUEUE);
        assertThat(scope.userId()).isEqualTo(agent.userId());
        assertThat(scope.teamIds()).containsExactly(support.getId());
        assertThat(scope.ownedNumberIds()).containsExactly(a.getId());
        assertThat(scopes.defaultScope(scope)).isEqualTo(ListScope.MINE);
    }

    @Test
    void mineLevelStillKnowsItsTeams() {
        Role viewOnly = new Role();
        viewOnly.setName("VIEW_ONLY");
        viewOnly.setLevel(RoleLevel.BRAND);
        viewOnly.setAuthorities(new java.util.HashSet<>(List.of("inbox:view")));
        roles.save(viewOnly);
        roleCache.evictAll();
        AuthenticatedUser user = brandUser("viewer", "VIEW_ONLY", brand);
        Team support = team(brand, "Support", user.userId());
        PhoneNumber a = number(brand, support);

        InboxScope scope = scopes.scopeOf(user, brand.getId());

        assertThat(scope.level()).isEqualTo(Level.MINE);
        assertThat(scope.teamIds()).containsExactly(support.getId());
        assertThat(scope.ownedNumberIds()).containsExactly(a.getId());
    }

    @Test
    void visibleNumbersAreOwnedPlusGuestNumbersWithCoveredChats() {
        AuthenticatedUser agent = brandUser("agent", BuiltInRoles.AGENT, brand);
        Team support = team(brand, "Support", agent.userId());
        Team sales = team(brand, "Sales", "someone-else");
        PhoneNumber a = number(brand, support);
        PhoneNumber c = number(brand, sales);
        PhoneNumber d = number(brand, sales);
        WaWebChat transferred = new WaWebChat();
        transferred.setCustomerId(acme.getId());
        transferred.setWorkspaceId(brand.getId());
        transferred.setPhoneNumberId(c.getId());
        transferred.setRemoteId("919911111111@c.us");
        transferred.setStatus(ChatStatus.OPEN);
        transferred.setAssignedTeamId(support.getId());
        chats.save(transferred);

        List<InboxScopeService.VisibleNumber> visible = scopes.visibleNumbers(agent, brand.getId());

        assertThat(visible).extracting(v -> v.number().getId()).containsExactlyInAnyOrder(a.getId(), c.getId());
        assertThat(visible).filteredOn(v -> v.number().getId().equals(a.getId())).singleElement()
                .satisfies(v -> assertThat(v.guest()).isFalse());
        assertThat(visible).filteredOn(v -> v.number().getId().equals(c.getId())).singleElement()
                .satisfies(v -> assertThat(v.guest()).isTrue());
        assertThat(visible).extracting(v -> v.number().getId()).doesNotContain(d.getId());
    }

    @Test
    void teamViewerGetsTeamWithOwnMemberships() {
        Role viewer = new Role();
        viewer.setName("TEAM_VIEWER");
        viewer.setLevel(RoleLevel.BRAND);
        viewer.setAuthorities(new java.util.HashSet<>(List.of("inbox:view", "inbox:view-team")));
        roles.save(viewer);
        roleCache.evictAll();
        AuthenticatedUser lead = brandUser("lead", "TEAM_VIEWER", brand);
        Team support = team(brand, "Support", lead.userId());
        team(brand, "Sales", "someone-else");
        team(other, "Elsewhere", lead.userId());

        PhoneNumber a = number(brand, support);

        InboxScope scope = scopes.scopeOf(lead, brand.getId());

        assertThat(scope.level()).isEqualTo(Level.TEAM);
        assertThat(scope.teamIds()).containsExactly(support.getId());
        assertThat(scope.ownedNumberIds()).containsExactly(a.getId());
        assertThat(scopes.defaultScope(scope)).isEqualTo(ListScope.TEAM);
    }

    @Test
    void workspaceManagerGetsAllWithEveryTeam() {
        AuthenticatedUser wm = brandUser("wm", BuiltInRoles.WORKSPACE_MANAGER, brand);
        Team support = team(brand, "Support", "x");
        Team sales = team(brand, "Sales", wm.userId());
        team(other, "Elsewhere", "x");
        PhoneNumber owned = number(brand, support);
        PhoneNumber unowned = number(brand, null);
        number(other, null);

        InboxScope scope = scopes.scopeOf(wm, brand.getId());

        assertThat(scope.level()).isEqualTo(Level.ALL);
        assertThat(scope.teamIds()).containsExactlyInAnyOrder(support.getId(), sales.getId());
        assertThat(scope.ownedNumberIds()).containsExactlyInAnyOrder(owned.getId(), unowned.getId());
        assertThat(scopes.defaultScope(scope)).isEqualTo(ListScope.ALL);
        assertThat(scopes.visibleNumbers(wm, brand.getId())).extracting(v -> v.number().getId())
                .containsExactlyInAnyOrder(owned.getId(), unowned.getId());
        assertThat(scopes.visibleNumbers(wm, brand.getId())).allSatisfy(v -> assertThat(v.guest()).isFalse());
    }

    @Test
    void userWithoutAccessIsNotFound() {
        AuthenticatedUser stranger = brandUser("stranger", BuiltInRoles.AGENT, other);

        assertThatThrownBy(() -> scopes.scopeOf(stranger, brand.getId())).isInstanceOf(NotFoundException.class);
    }

    @Test
    void brandRoleWithoutInboxViewIsNotFound() {
        Role custom = new Role();
        custom.setName("No inbox");
        custom.setLevel(RoleLevel.BRAND);
        custom.setAuthorities(Set.of("workspace:settings"));
        roles.save(custom);
        roleCache.evictAll();
        AuthenticatedUser user = brandUser("noinbox", "No inbox", brand);

        assertThatThrownBy(() -> scopes.scopeOf(user, brand.getId()))
                .isInstanceOf(NotFoundException.class).hasMessage("workspace not found");
    }
}
