package me.nageshk.omnichannel.inbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import me.nageshk.omnichannel.access.BuiltInRoles;
import me.nageshk.omnichannel.phone.PhoneNumber;
import me.nageshk.omnichannel.phone.PhoneNumberRepository;
import me.nageshk.omnichannel.support.TestUsers;
import me.nageshk.omnichannel.tenant.Customer;
import me.nageshk.omnichannel.tenant.Team;
import me.nageshk.omnichannel.tenant.TeamRepository;
import me.nageshk.omnichannel.tenant.Workspace;
import me.nageshk.omnichannel.user.Availability;
import me.nageshk.omnichannel.user.User;
import me.nageshk.omnichannel.user.UserRepository;
import me.nageshk.omnichannel.user.UserService;
import me.nageshk.omnichannel.user.WorkspaceRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

/** An agent coming back from Away picks up the round-robin backlog of their teams (design §3.2). */
@SpringBootTest
class AvailabilityRoutingIT {

    @Autowired UserService userService;
    @Autowired InboxSettingService settings;
    @Autowired TestUsers testUsers;
    @Autowired TeamRepository teams;
    @Autowired UserRepository users;
    @Autowired PhoneNumberRepository phoneNumbers;
    @Autowired WaWebChatRepository chats;
    @Autowired WaWebChatActionRepository actions;
    @Autowired MongoTemplate mongo;
    @Autowired Clock clock;

    Customer acme;
    Workspace brand;
    PhoneNumber pn;
    User p;
    User r;
    Team sales;

    @BeforeEach
    void setUp() {
        clean();
        acme = testUsers.customer("Acme");
        brand = testUsers.workspace(acme.getId(), "Brand A");
        pn = testUsers.phoneNumber(acme.getId(), brand.getId(), "9199000" + System.nanoTime() % 100000);
        p = agent("p");
        r = agent("r");
        sales = team("Sales", p, r);
        pn.setTeamId(sales.getId());
        phoneNumbers.save(pn);
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    private void clean() {
        testUsers.deleteAll();
        for (Class<?> c : List.of(WaWebChat.class, WaWebChatAction.class, InboxSetting.class))
            mongo.remove(new Query(), c);
    }

    private User agent(String username) {
        return testUsers.createInCustomer(acme.getId(), username, null,
                List.of(new WorkspaceRole(brand.getId(), testUsers.roleId(BuiltInRoles.AGENT))));
    }

    private Team team(String name, User... members) {
        Team t = new Team();
        t.setCustomerId(acme.getId());
        t.setWorkspaceId(brand.getId());
        t.setName(name);
        t.setMemberIds(new ArrayList<>(Arrays.stream(members).map(User::getId).toList()));
        return teams.save(t);
    }

    private void roundRobin(boolean on) {
        settings.putOverride(brand.getId(), sales.getId(), new RoutingSetting.Request(true, on, null, null, null, null));
    }

    private void away(User u) {
        u.setAvailability(new Availability(Availability.Status.AWAY, clock.instant()));
        users.save(u);
    }

    private WaWebChat queued(String digits, long minutesAgo) {
        WaWebChat c = new WaWebChat();
        c.setCustomerId(acme.getId());
        c.setWorkspaceId(brand.getId());
        c.setPhoneNumberId(pn.getId());
        c.setRemoteId(digits + "@c.us");
        c.setStatus(ChatStatus.OPEN);
        c.setAssignedTeamId(sales.getId());
        c.setLastMessageAt(Instant.parse("2026-10-01T10:00:00Z").minusSeconds(minutesAgo * 60));
        return chats.save(c);
    }

    private String assignee(WaWebChat c) {
        return chats.findById(c.getId()).orElseThrow().getAssignedUserId();
    }

    @Test
    void comingBackAvailableDrainsRoundRobinQueueToTheOnlyAvailableMember() {
        roundRobin(true);
        away(p);
        away(r);
        WaWebChat oldest = queued("919911111111", 30);
        WaWebChat middle = queued("919922222222", 20);
        WaWebChat newest = queued("919933333333", 10);

        userService.setAvailability("p", Availability.Status.AVAILABLE);

        assertThat(assignee(oldest)).isEqualTo(p.getId());
        assertThat(assignee(middle)).isEqualTo(p.getId());
        assertThat(assignee(newest)).isEqualTo(p.getId());
        assertThat(actions.findAll()).extracting(WaWebChatAction::getType).containsOnly(ActionType.ROUTED);
    }

    @Test
    void comingBackAvailableSharesTheQueueWithOtherAvailableMembers() {
        roundRobin(true);
        away(p);
        WaWebChat oldest = queued("919911111111", 30);
        WaWebChat middle = queued("919922222222", 20);
        WaWebChat newest = queued("919933333333", 10);

        userService.setAvailability("p", Availability.Status.AVAILABLE);

        // oldest first, normal rotation over p and r
        assertThat(assignee(oldest)).isEqualTo(p.getId());
        assertThat(assignee(middle)).isEqualTo(r.getId());
        assertThat(assignee(newest)).isEqualTo(p.getId());
    }

    @Test
    void teamOnlySettingDoesNotAutoAssign() {
        roundRobin(false);
        away(p);
        WaWebChat c = queued("919911111111", 30);

        userService.setAvailability("p", Availability.Status.AVAILABLE);

        assertThat(assignee(c)).isNull();
        assertThat(actions.count()).isZero();
    }

    @Test
    void goingAwayOrStayingAwayDoesNothing() {
        roundRobin(true);
        WaWebChat c = queued("919911111111", 30);

        userService.setAvailability("p", Availability.Status.AWAY);
        userService.setAvailability("p", Availability.Status.AWAY);

        assertThat(assignee(c)).isNull();
        assertThat(actions.count()).isZero();
    }

    @Test
    void onlyOpenQueuedChatsOfTheTeamAreTouched() {
        roundRobin(true);
        away(p);
        away(r);
        WaWebChat closed = queued("919911111111", 30);
        closed.setStatus(ChatStatus.CLOSED);
        chats.save(closed);
        WaWebChat theirs = queued("919922222222", 20);
        theirs.setAssignedUserId(r.getId());
        chats.save(theirs);
        WaWebChat queuedChat = queued("919933333333", 10);

        userService.setAvailability("p", Availability.Status.AVAILABLE);

        assertThat(assignee(closed)).isNull();
        assertThat(assignee(theirs)).isEqualTo(r.getId());
        assertThat(assignee(queuedChat)).isEqualTo(p.getId());
    }
}
