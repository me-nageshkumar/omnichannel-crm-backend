package me.nageshk.omnichannel.app.inbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import me.nageshk.omnichannel.app.support.TestUsers;
import me.nageshk.omnichannel.db.inbox.ActionType;
import me.nageshk.omnichannel.db.inbox.ChatStatus;
import me.nageshk.omnichannel.db.inbox.InboxSetting;
import me.nageshk.omnichannel.db.inbox.RoutedReason;
import me.nageshk.omnichannel.db.inbox.RoutingMode;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import me.nageshk.omnichannel.db.inbox.WaWebChatAction;
import me.nageshk.omnichannel.db.inbox.WaWebChatActionRepository;
import me.nageshk.omnichannel.db.inbox.WaWebChatRepository;
import me.nageshk.omnichannel.db.phone.PhoneNumber;
import me.nageshk.omnichannel.db.phone.PhoneNumberRepository;
import me.nageshk.omnichannel.db.tenant.Customer;
import me.nageshk.omnichannel.db.tenant.Team;
import me.nageshk.omnichannel.db.tenant.TeamRepository;
import me.nageshk.omnichannel.db.tenant.Workspace;
import me.nageshk.omnichannel.db.user.User;
import me.nageshk.omnichannel.db.user.WorkspaceRole;
import me.nageshk.omnichannel.security.access.BuiltInRoles;
import me.nageshk.omnichannel.service.inbox.InboxSettingService;
import me.nageshk.omnichannel.service.inbox.RoutingSetting;
import me.nageshk.omnichannel.service.phone.PhoneNumberService;
import me.nageshk.omnichannel.service.tenant.TeamService;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

/** A number changing hands, or a member leaving, never leaves a chat with someone who cannot open it (design §3.1). */
@SpringBootTest
class OwnershipRoutingIT {

    @Autowired PhoneNumberService phoneNumberService;
    @Autowired PhoneNumberRepository phoneNumbers;
    @Autowired TeamService teamService;
    @Autowired TeamRepository teams;
    @Autowired InboxSettingService settings;
    @Autowired WaWebChatRepository chats;
    @Autowired WaWebChatActionRepository actions;
    @Autowired TestUsers testUsers;
    @Autowired MongoTemplate mongo;

    Customer acme;
    Workspace brand;
    PhoneNumber pn;
    User p;
    User q;
    User s;
    User boss;
    Team support;
    Team sales;

    @BeforeEach
    void setUp() {
        clean();
        acme = testUsers.customer("Acme");
        brand = testUsers.workspace(acme.getId(), "Brand A");
        pn = testUsers.phoneNumber(acme.getId(), brand.getId(), "9199000" + System.nanoTime() % 100000);
        p = agent("p", BuiltInRoles.AGENT);
        q = agent("q", BuiltInRoles.AGENT);
        s = agent("s", BuiltInRoles.AGENT);
        boss = agent("boss", BuiltInRoles.WORKSPACE_MANAGER);
        support = team("Support", p, q);
        sales = team("Sales", s);
        pn = phoneNumberService.setTeam(pn, support.getId());
        actions.deleteAll();
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    private void clean() {
        testUsers.deleteAll();
        for (Class<?> c : List.of(WaWebChat.class, WaWebChatAction.class, InboxSetting.class))
            mongo.remove(new Query(), c);
        mongo.remove(new Query(), "inboxSettingsLegacy");
    }

    private User agent(String username, String role) {
        return testUsers.createInCustomer(acme.getId(), username, null,
                List.of(new WorkspaceRole(brand.getId(), testUsers.roleId(role))));
    }

    private Team team(String name, User... members) {
        Team t = new Team();
        t.setCustomerId(acme.getId());
        t.setWorkspaceId(brand.getId());
        t.setName(name);
        t.setMemberIds(new ArrayList<>(Arrays.stream(members).map(User::getId).toList()));
        return teams.save(t);
    }

    private void roundRobin(Team t) {
        settings.putOverride(brand.getId(), t.getId(), new RoutingSetting.Request(true, true, null, null, null, null));
    }

    private WaWebChat chat(String digits, ChatStatus status, User user, Team team) {
        WaWebChat c = new WaWebChat();
        c.setCustomerId(acme.getId());
        c.setWorkspaceId(brand.getId());
        c.setPhoneNumberId(pn.getId());
        c.setRemoteId(digits + "@c.us");
        c.setStatus(status);
        c.setAssignedUserId(user == null ? null : user.getId());
        c.setAssignedTeamId(team == null ? null : team.getId());
        return chats.save(c);
    }

    private WaWebChat reload(WaWebChat c) {
        return chats.findById(c.getId()).orElseThrow();
    }

    @Test
    void ownerChangeReroutesOpenChatsOfOldMembers() {
        roundRobin(sales);
        WaWebChat open = chat("919911111111", ChatStatus.OPEN, p, support);
        WaWebChat queued = chat("919922222222", ChatStatus.OPEN, null, support);
        WaWebChat closed = chat("919933333333", ChatStatus.CLOSED, p, support);

        phoneNumberService.setTeam(reload(open).getPhoneNumberId() == null ? pn : phoneNumbers.findById(pn.getId()).orElseThrow(),
                sales.getId());

        assertThat(reload(open).getAssignedUserId()).isEqualTo(s.getId());
        assertThat(reload(open).getAssignedTeamId()).isEqualTo(sales.getId());
        assertThat(reload(queued).getAssignedUserId()).isEqualTo(s.getId()); // the old team's queue moves too
        assertThat(reload(closed).getAssignedUserId()).isEqualTo(p.getId()); // closed chats are history
        assertThat(actions.findAll()).hasSize(2).allSatisfy(a -> {
            assertThat(a.getType()).isEqualTo(ActionType.ROUTED);
            assertThat(a.getReason()).isEqualTo(RoutedReason.OWNER_CHANGED);
        });
    }

    @Test
    void ownerChangeKeepsChatsOfUsersStillAbleToSee() {
        WaWebChat bosses = chat("919911111111", ChatStatus.OPEN, boss, support);

        phoneNumberService.setTeam(phoneNumbers.findById(pn.getId()).orElseThrow(), sales.getId());

        assertThat(reload(bosses).getAssignedUserId()).isEqualTo(boss.getId());
        assertThat(actions.count()).isZero();
    }

    @Test
    void ownerClearedUnassignsOpenChats() {
        WaWebChat open = chat("919911111111", ChatStatus.OPEN, p, support);

        phoneNumberService.setTeam(phoneNumbers.findById(pn.getId()).orElseThrow(), null);

        WaWebChat after = reload(open);
        assertThat(after.getAssignedUserId()).isNull();
        assertThat(after.getAssignedTeamId()).isNull();
        assertThat(actions.findAll()).singleElement().satisfies(a -> {
            assertThat(a.getType()).isEqualTo(ActionType.UNASSIGNED);
            assertThat(a.getByUserId()).isNull();
        });
    }

    @Test
    void memberRemovalReroutesTheirOpenChats() {
        roundRobin(support);
        WaWebChat open = chat("919911111111", ChatStatus.OPEN, p, support);
        WaWebChat closed = chat("919922222222", ChatStatus.CLOSED, p, support);

        teamService.update(acme.getId(), brand.getId(), support.getId(), "Support", List.of(q.getId()));

        assertThat(reload(open).getAssignedUserId()).isEqualTo(q.getId());
        assertThat(reload(closed).getAssignedUserId()).isEqualTo(p.getId());
        assertThat(actions.findAll()).singleElement().satisfies(a -> {
            assertThat(a.getType()).isEqualTo(ActionType.ROUTED);
            assertThat(a.getReason()).isEqualTo(RoutedReason.MEMBER_REMOVED);
            assertThat(a.getExcludedUserId()).isEqualTo(p.getId());
        });
    }

    @Test
    void memberRemovalWithRoutingOffUnassigns() {
        settings.putOverride(brand.getId(), support.getId(),
                new RoutingSetting.Request(false, false, null, null, null, null));
        WaWebChat open = chat("919911111111", ChatStatus.OPEN, p, support);

        teamService.update(acme.getId(), brand.getId(), support.getId(), "Support", List.of(q.getId()));

        assertThat(reload(open).getAssignedUserId()).isNull();
        assertThat(reload(open).getAssignedTeamId()).isEqualTo(support.getId());
        assertThat(actions.findAll()).singleElement().satisfies(a -> assertThat(a.getType()).isEqualTo(ActionType.UNASSIGNED));
    }

    @Test
    void legacyRoutingSeedsTeamOverrideOnFirstOwnerAssignment() {
        PhoneNumber fresh = testUsers.phoneNumber(acme.getId(), brand.getId(), "9188000" + System.nanoTime() % 100000);
        mongo.insert(new Document("customerId", acme.getId()).append("workspaceId", brand.getId())
                .append("phoneNumberId", fresh.getId())
                .append("routing", new Document("mode", "ROUND_ROBIN").append("teamId", sales.getId()).append("userId", null))
                .append("claimOnReply", false).append("keepAssignmentOnClose", true), "inboxSettingsLegacy");

        // a different owner than the legacy routing named: nothing is seeded
        phoneNumberService.setTeam(fresh, support.getId());
        assertThat(settings.override(brand.getId(), support.getId())).isEmpty();

        phoneNumberService.setTeam(phoneNumbers.findById(fresh.getId()).orElseThrow(), sales.getId());
        InboxSetting seeded = settings.override(brand.getId(), sales.getId()).orElseThrow();
        assertThat(seeded.getRouting()).isEqualTo(new InboxSetting.Routing(RoutingMode.ROUND_ROBIN, null));
        assertThat(seeded.isClaimOnReply()).isFalse();
        assertThat(seeded.isKeepAssignmentOnClose()).isTrue();

        // an existing override is never overwritten
        settings.putOverride(brand.getId(), sales.getId(), new RoutingSetting.Request(true, false, null, 3, null, null));
        phoneNumberService.setTeam(phoneNumbers.findById(fresh.getId()).orElseThrow(), support.getId());
        phoneNumberService.setTeam(phoneNumbers.findById(fresh.getId()).orElseThrow(), sales.getId());
        assertThat(settings.override(brand.getId(), sales.getId()).orElseThrow().getStickyDays()).isEqualTo(3);
    }

    @Test
    void legacyUserModeKeepsTheMemberOnlyWhileStillAMember() {
        PhoneNumber fresh = testUsers.phoneNumber(acme.getId(), brand.getId(), "9177000" + System.nanoTime() % 100000);
        mongo.insert(new Document("customerId", acme.getId()).append("workspaceId", brand.getId())
                .append("phoneNumberId", fresh.getId())
                .append("routing", new Document("mode", "USER").append("teamId", sales.getId()).append("userId", p.getId()))
                .append("claimOnReply", true).append("keepAssignmentOnClose", false), "inboxSettingsLegacy");

        phoneNumberService.setTeam(fresh, sales.getId()); // p is not in Sales

        assertThat(settings.override(brand.getId(), sales.getId()).orElseThrow().getRouting())
                .isEqualTo(new InboxSetting.Routing(RoutingMode.TEAM, null));
    }

    @Test
    void ownerChangeRotatesReroutedChatsOverTheNewTeam() {
        User s2 = agent("s2", BuiltInRoles.AGENT);
        Team wideSales = team("Wide sales", s, s2);
        roundRobin(wideSales);
        WaWebChat one = chat("919911111111", ChatStatus.OPEN, p, support);
        WaWebChat two = chat("919922222222", ChatStatus.OPEN, p, support);
        WaWebChat three = chat("919933333333", ChatStatus.OPEN, q, support);

        phoneNumberService.setTeam(phoneNumbers.findById(pn.getId()).orElseThrow(), wideSales.getId());

        List<String> assignees = List.of(reload(one).getAssignedUserId(), reload(two).getAssignedUserId(),
                reload(three).getAssignedUserId());
        assertThat(assignees).containsExactlyInAnyOrder(s.getId(), s2.getId(), s.getId());
        assertThat(assignees.get(0)).isNotEqualTo(assignees.get(1));
    }

    @Test
    void memberRemovalRotatesReroutedChatsOverTheRemainingMembers() {
        User r = agent("r", BuiltInRoles.AGENT);
        Team trio = team("Trio", p, q, r);
        pn = phoneNumberService.setTeam(phoneNumbers.findById(pn.getId()).orElseThrow(), trio.getId());
        actions.deleteAll();
        roundRobin(trio);
        WaWebChat one = chat("919911111111", ChatStatus.OPEN, p, trio);
        WaWebChat two = chat("919922222222", ChatStatus.OPEN, p, trio);

        teamService.update(acme.getId(), brand.getId(), trio.getId(), "Trio", List.of(q.getId(), r.getId()));

        assertThat(List.of(reload(one).getAssignedUserId(), reload(two).getAssignedUserId()))
                .containsExactlyInAnyOrder(q.getId(), r.getId());
    }
}
