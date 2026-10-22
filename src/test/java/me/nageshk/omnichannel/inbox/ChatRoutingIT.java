package me.nageshk.omnichannel.inbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import me.nageshk.omnichannel.access.BuiltInRoles;
import me.nageshk.omnichannel.events.WhatsAppEvent;
import me.nageshk.omnichannel.support.TestUsers;
import me.nageshk.omnichannel.tenant.Customer;
import me.nageshk.omnichannel.phone.PhoneNumber;
import me.nageshk.omnichannel.phone.PhoneNumberRepository;
import me.nageshk.omnichannel.tenant.Team;
import me.nageshk.omnichannel.tenant.TeamRepository;
import me.nageshk.omnichannel.tenant.Workspace;
import me.nageshk.omnichannel.user.User;
import me.nageshk.omnichannel.user.UserRepository;
import me.nageshk.omnichannel.user.WorkspaceRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@SpringBootTest
class ChatRoutingIT {

    private static final long T = 1_700_000_000L;

    static class ActionRecorder {
        final List<InboxEvents.ActionRecorded> actions = new CopyOnWriteArrayList<>();
        final List<InboxEvents.ChatChanged> changes = new CopyOnWriteArrayList<>();

        @EventListener
        void on(InboxEvents.ActionRecorded e) {
            actions.add(e);
        }

        @EventListener
        void on(InboxEvents.ChatChanged e) {
            changes.add(e);
        }
    }

    @TestConfiguration
    static class RecorderConfig {
        @Bean
        ActionRecorder routingRecorder() {
            return new ActionRecorder();
        }
    }

    @Autowired ApplicationEventPublisher publisher;
    @Autowired ChatRoutingService routing;
    @Autowired InboxSettingService settings;
    @Autowired PhoneNumberRepository phoneNumbers;
    @Autowired WaWebChatRepository chats;
    @Autowired WaWebChatActionRepository actions;
    @Autowired TeamRepository teams;
    @Autowired UserRepository users;
    @Autowired TestUsers testUsers;
    @Autowired MongoTemplate mongo;
    @Autowired ObjectMapper mapper;
    @Autowired ActionRecorder recorder;
    @Autowired me.nageshk.omnichannel.user.UserAdminService userAdmin;
    @Autowired java.time.Clock clock;

    Customer acme;
    Workspace brand;
    Workspace other;
    String pn;

    @BeforeEach
    void setUp() {
        clean();
        acme = testUsers.customer("Acme");
        brand = testUsers.workspace(acme.getId(), "Brand A");
        other = testUsers.workspace(acme.getId(), "Brand B");
        pn = testUsers.phoneNumber(acme.getId(), brand.getId(), "9199000" + System.nanoTime() % 100000).getId();
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    private void clean() {
        testUsers.deleteAll();
        for (Class<?> c : List.of(WaWebChat.class, WaWebMessage.class, WaWebChatAction.class, InboxSetting.class))
            mongo.remove(new Query(), c);
        recorder.actions.clear();
        recorder.changes.clear();
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
        t.setMemberIds(new ArrayList<>(java.util.Arrays.stream(members).map(User::getId).toList()));
        return teams.save(t);
    }

    /** {@code team} owns the number and gets an override with the given switches. */
    private void configure(Team team, RoutingMode mode, String userId) {
        PhoneNumber number = phoneNumbers.findById(pn).orElseThrow();
        number.setTeamId(team.getId());
        phoneNumbers.save(number);
        settings.putOverride(brand.getId(), team.getId(), new RoutingSetting.Request(mode != RoutingMode.NONE,
                mode == RoutingMode.ROUND_ROBIN, mode == RoutingMode.USER ? userId : null, null, null, null));
    }

    private String cursorOf(Team team) {
        return teams.findById(team.getId()).orElseThrow().getRoundRobinCursor();
    }

    private void setCursor(Team team, String userId) {
        Team t = teams.findById(team.getId()).orElseThrow();
        t.setRoundRobinCursor(userId);
        teams.save(t);
    }

    private String secondNumber() {
        return testUsers.phoneNumber(acme.getId(), brand.getId(), "9188000" + System.nanoTime() % 100000).getId();
    }

    private void owned(String phoneNumberId, Team team) {
        PhoneNumber number = phoneNumbers.findById(phoneNumberId).orElseThrow();
        number.setTeamId(team.getId());
        phoneNumbers.save(number);
    }

    private void away(User u) {
        u.setAvailability(new me.nageshk.omnichannel.user.Availability(
                me.nageshk.omnichannel.user.Availability.Status.AWAY, clock.instant()));
        users.save(u);
    }

    private void lastHandledBy(WaWebChat c, User agent, java.time.Duration ago) {
        c.setLastAgentUserId(agent.getId());
        c.setLastAgentAt(clock.instant().minus(ago));
        chats.save(c);
    }

    private void message(String digits, boolean fromMe, long epochSeconds) {
        message(pn, digits, fromMe, epochSeconds);
    }

    private void message(String phoneNumberId, String digits, boolean fromMe, long epochSeconds) {
        ObjectNode p = mapper.createObjectNode();
        ObjectNode id = p.putObject("id");
        id.put("fromMe", fromMe);
        id.put("remote", digits + "@c.us");
        id.put("id", "RT" + epochSeconds);
        p.put("fromMe", fromMe);
        p.put("timestamp", epochSeconds);
        p.put("body", "hi");
        publisher.publishEvent(
                new WhatsAppEvent(this, phoneNumberId, brand.getId(), acme.getId(), "message_create", p, null, null));
    }

    private WaWebChat chat(String digits) {
        return chat(pn, digits);
    }

    private WaWebChat chat(String phoneNumberId, String digits) {
        return chats.findByPhoneNumberIdAndRemoteId(phoneNumberId, digits + "@c.us").orElseThrow();
    }

    private WaWebChatAction lastRouted() {
        return actions.findAll().stream().filter(a -> a.getType() == ActionType.ROUTED)
                .max(java.util.Comparator.comparing(WaWebChatAction::getAt)).orElseThrow();
    }

    @Test
    void unownedNumberLeavesChatUnassigned() {
        message("919911111111", false, T);

        WaWebChat c = chat("919911111111");
        assertThat(c.getAssignedUserId()).isNull();
        assertThat(c.getAssignedTeamId()).isNull();
        assertThat(c.getAssignedAt()).isNull();
        assertThat(actions.findAll()).isEmpty();
    }

    @Test
    void noneOnAnOwnedNumberLeavesUnassigned() {
        Team support = team("Support", agent("a"));
        configure(support, RoutingMode.NONE, null);

        message("919911111111", false, T);

        assertThat(chat("919911111111").getAssignedTeamId()).isNull();
        assertThat(actions.findAll()).isEmpty();
    }

    @Test
    void teamModeQueuesOnTeam() {
        Team support = team("Support", agent("a"));
        configure(support, RoutingMode.TEAM, null);

        message("919911111111", false, T);

        WaWebChat c = chat("919911111111");
        assertThat(c.getAssignedTeamId()).isEqualTo(support.getId());
        assertThat(c.getAssignedUserId()).isNull();
        assertThat(c.getAssignedAt()).isNotNull();
        assertThat(actions.findAll()).singleElement().satisfies(a -> {
            assertThat(a.getType()).isEqualTo(ActionType.ROUTED);
            assertThat(a.getToTeamId()).isEqualTo(support.getId());
            assertThat(a.getToTeamName()).isEqualTo("Support");
            assertThat(a.getToUserId()).isNull();
        });
    }

    @Test
    void userModeAssignsUser() {
        User a = agent("a");
        Team solo = team("Solo", a);
        configure(solo, RoutingMode.USER, a.getId());

        message("919911111111", false, T);

        WaWebChat c = chat("919911111111");
        assertThat(c.getAssignedUserId()).isEqualTo(a.getId());
        assertThat(c.getAssignedTeamId()).isEqualTo(solo.getId());
        assertThat(actions.findAll()).singleElement().satisfies(x -> {
            assertThat(x.getType()).isEqualTo(ActionType.ROUTED);
            assertThat(x.getToUserId()).isEqualTo(a.getId());
            assertThat(x.getToUserName()).isEqualTo("Test a");
        });
    }

    @Test
    void userModeWithADisabledUserQueuesOnTheTeam() {
        User a = agent("a");
        Team solo = team("Solo", a);
        configure(solo, RoutingMode.USER, a.getId());
        a.setActive(false);
        users.save(a);

        message("919911111111", false, T);

        WaWebChat c = chat("919911111111");
        assertThat(c.getAssignedUserId()).isNull();
        assertThat(c.getAssignedTeamId()).isEqualTo(solo.getId());
        assertThat(actions.findAll()).singleElement().satisfies(x -> assertThat(x.getToUserId()).isNull());
    }

    @Test
    void userModeWithABrandDisabledOrUnreachableUserQueuesOnTheTeam() {
        User blocked = agent("blocked");
        User away = agent("away");
        Team solo = team("Solo", blocked, away);
        configure(solo, RoutingMode.USER, blocked.getId());
        blocked.setBrandDisables(List.of(new me.nageshk.omnichannel.user.BrandDisable(brand.getId(),
                new me.nageshk.omnichannel.enablement.Disabled("x",
                        me.nageshk.omnichannel.enablement.EnableLevel.COMPANY, java.time.Instant.now()))));
        users.save(blocked);
        message("919911111111", false, T);

        configure(solo, RoutingMode.USER, away.getId());
        away.setWorkspaceRoles(List.of(new WorkspaceRole(other.getId(), testUsers.roleId(BuiltInRoles.AGENT))));
        users.save(away);
        message("919922222222", false, T + 1);

        assertThat(chat("919911111111").getAssignedUserId()).isNull();
        assertThat(chat("919911111111").getAssignedTeamId()).isEqualTo(solo.getId());
        assertThat(chat("919922222222").getAssignedUserId()).isNull();
        assertThat(chat("919922222222").getAssignedTeamId()).isEqualTo(solo.getId());
    }

    @Test
    void userModeWithAnIneligibleUserQueuesOnTheOwningTeam() {
        User a = agent("a");
        Team support = team("Support", a);
        configure(support, RoutingMode.USER, a.getId());
        a.setActive(false);
        users.save(a);

        message("919911111111", false, T);

        WaWebChat c = chat("919911111111");
        assertThat(c.getAssignedUserId()).isNull();
        assertThat(c.getAssignedTeamId()).isEqualTo(support.getId());
    }

    @Test
    void roundRobinCursorIsStoredOnTheTeam() {
        User a = agent("a");
        Team support = team("Support", a);
        configure(support, RoutingMode.ROUND_ROBIN, null);

        message("919911111111", false, T);

        assertThat(cursorOf(support)).isEqualTo(a.getId());
        Team now = teams.findById(support.getId()).orElseThrow();
        assertThat(now.getName()).isEqualTo("Support");
        assertThat(now.getMemberIds()).containsExactly(a.getId());
        assertThat(mongo.count(new Query(), InboxSetting.class)).isEqualTo(2); // brand default + override
    }

    @Test
    void roundRobinRotatesAndSkipsDisabled() {
        User a = agent("a");
        User b = agent("b");
        User c = agent("c");
        Team support = team("Support", a, b, c);
        b.setActive(false);
        users.save(b);
        configure(support, RoutingMode.ROUND_ROBIN, null);

        message("919911111111", false, T);
        message("919922222222", false, T + 1);
        message("919933333333", false, T + 2);

        assertThat(chat("919911111111").getAssignedUserId()).isEqualTo(a.getId());
        assertThat(chat("919922222222").getAssignedUserId()).isEqualTo(c.getId());
        assertThat(chat("919933333333").getAssignedUserId()).isEqualTo(a.getId());
        assertThat(chat("919911111111").getAssignedTeamId()).isEqualTo(support.getId());
        assertThat(cursorOf(support)).isEqualTo(a.getId());
    }

    @Test
    void roundRobinSkipsBrandDisabledAndUsersWithoutAccess() {
        User a = agent("a");
        User b = agent("b");
        User c = agent("c");
        Team support = team("Support", a, b, c);
        b.setWorkspaceRoles(List.of(new WorkspaceRole(other.getId(), testUsers.roleId(BuiltInRoles.AGENT))));
        users.save(b);
        a.setBrandDisables(List.of(new me.nageshk.omnichannel.user.BrandDisable(brand.getId(),
                new me.nageshk.omnichannel.enablement.Disabled("x",
                        me.nageshk.omnichannel.enablement.EnableLevel.COMPANY, java.time.Instant.now()))));
        users.save(a);
        configure(support, RoutingMode.ROUND_ROBIN, null);

        message("919911111111", false, T);
        message("919922222222", false, T + 1);

        assertThat(chat("919911111111").getAssignedUserId()).isEqualTo(c.getId());
        assertThat(chat("919922222222").getAssignedUserId()).isEqualTo(c.getId());
    }

    @Test
    void roundRobinWithNoEligibleMemberQueuesOnTeam() {
        User a = agent("a");
        Team support = team("Support", a);
        a.setActive(false);
        users.save(a);
        configure(support, RoutingMode.ROUND_ROBIN, null);

        message("919911111111", false, T);

        WaWebChat c = chat("919911111111");
        assertThat(c.getAssignedUserId()).isNull();
        assertThat(c.getAssignedTeamId()).isEqualTo(support.getId());
        assertThat(actions.findAll()).singleElement().satisfies(x -> {
            assertThat(x.getType()).isEqualTo(ActionType.ROUTED);
            assertThat(x.getToUserId()).isNull();
            assertThat(x.getToTeamId()).isEqualTo(support.getId());
        });
    }

    @Test
    void outboundFirstMessageIsNotRouted() {
        User a = agent("a");
        configure(team("Solo", a), RoutingMode.USER, a.getId());

        message("919911111111", true, T);

        WaWebChat c = chat("919911111111");
        assertThat(c.getAssignedUserId()).isNull();
        assertThat(actions.findAll()).isEmpty();
    }

    @Test
    void reopenedUnassignedChatIsRouted() {
        User a = agent("a");
        message("919911111111", false, T);
        WaWebChat c = chat("919911111111");
        c.setStatus(ChatStatus.CLOSED);
        chats.save(c);
        configure(team("Solo", a), RoutingMode.USER, a.getId());

        message("919911111111", false, T + 1);

        WaWebChat after = chat("919911111111");
        assertThat(after.getStatus()).isEqualTo(ChatStatus.OPEN);
        assertThat(after.getAssignedUserId()).isEqualTo(a.getId());
        assertThat(actions.findAll()).extracting(WaWebChatAction::getType)
                .containsExactlyInAnyOrder(ActionType.REOPENED, ActionType.ROUTED);
        assertThat(recorder.changes.getLast().chat().getAssignedUserId()).isEqualTo(a.getId());
    }

    @Test
    void reopenedAssignedChatIsNotRouted() {
        User a = agent("a");
        User b = agent("b");
        configure(team("Solo", a), RoutingMode.USER, a.getId());
        message("919911111111", false, T);
        WaWebChat c = chat("919911111111");
        c.setStatus(ChatStatus.CLOSED);
        c.setAssignedUserId(b.getId());
        chats.save(c);

        message("919911111111", false, T + 1);

        assertThat(chat("919911111111").getAssignedUserId()).isEqualTo(b.getId());
        assertThat(actions.findAll()).extracting(WaWebChatAction::getType)
                .containsExactlyInAnyOrder(ActionType.ROUTED, ActionType.REOPENED);
    }

    @Test
    void routedActionIsRecordedBySystem() {
        User a = agent("a");
        configure(team("Solo", a), RoutingMode.USER, a.getId());

        message("919911111111", false, T);

        assertThat(actions.findAll()).singleElement().satisfies(x -> {
            assertThat(x.getType()).isEqualTo(ActionType.ROUTED);
            assertThat(x.getByUserId()).isNull();
            assertThat(x.getByName()).isNull();
            assertThat(x.getChatId()).isEqualTo(chat("919911111111").getId());
            assertThat(x.getWorkspaceId()).isEqualTo(brand.getId());
            assertThat(x.getAt()).isNotNull();
        });
        assertThat(recorder.actions).singleElement()
                .satisfies(e -> assertThat(e.action().getType()).isEqualTo(ActionType.ROUTED));
    }

    @Test
    void roundRobinRestartsAtFirstMemberWhenCursorUserLeftTheTeam() {
        User a = agent("a");
        User b = agent("b");
        User gone = agent("gone");
        Team support = team("Support", a, b);
        configure(support, RoutingMode.ROUND_ROBIN, null);
        setCursor(support, gone.getId());

        message("919911111111", false, T);

        assertThat(chat("919911111111").getAssignedUserId()).isEqualTo(a.getId());
        assertThat(cursorOf(support)).isEqualTo(a.getId());
    }

    @Test
    void roundRobinCursorStaysWhenNoMemberIsEligible() {
        User a = agent("a");
        User b = agent("b");
        Team support = team("Support", a, b);
        configure(support, RoutingMode.ROUND_ROBIN, null);
        setCursor(support, a.getId());
        for (User u : List.of(a, b)) {
            u.setActive(false);
            users.save(u);
        }

        message("919911111111", false, T);

        assertThat(chat("919911111111").getAssignedTeamId()).isEqualTo(support.getId());
        assertThat(cursorOf(support)).isEqualTo(a.getId());
    }

    @Test
    void aDeletedOwningTeamLeavesTheChatUntouched() {
        Team support = team("Support", agent("a"));
        configure(support, RoutingMode.TEAM, null);
        teams.deleteById(support.getId());

        message("919911111111", false, T);

        WaWebChat c = chat("919911111111");
        assertThat(c.getAssignedTeamId()).isNull();
        assertThat(c.getAssignedUserId()).isNull();
        assertThat(actions.findAll()).isEmpty();
    }

    @Test
    void userModeWithADeletedUserQueuesOnTheTeam() {
        User a = agent("a");
        Team solo = team("Solo", a);
        configure(solo, RoutingMode.USER, a.getId());
        users.deleteById(a.getId());

        message("919911111111", false, T);

        WaWebChat c = chat("919911111111");
        assertThat(c.getAssignedUserId()).isNull();
        assertThat(c.getAssignedTeamId()).isEqualTo(solo.getId());
    }

    @Test
    void roundRobinRotatesAcrossTwoNumbersOfTheTeam() {
        User p = agent("p");
        User q = agent("q");
        User r = agent("r");
        Team sales = team("Sales", p, q, r);
        away(q);
        configure(sales, RoutingMode.ROUND_ROBIN, null);
        String pn2 = secondNumber();
        owned(pn2, sales);

        message(pn, "919911111111", false, T);
        message(pn2, "919922222222", false, T + 1);
        message(pn, "919933333333", false, T + 2);
        message(pn2, "919944444444", false, T + 3);

        assertThat(chat(pn, "919911111111").getAssignedUserId()).isEqualTo(p.getId());
        assertThat(chat(pn2, "919922222222").getAssignedUserId()).isEqualTo(r.getId());
        assertThat(chat(pn, "919933333333").getAssignedUserId()).isEqualTo(p.getId());
        assertThat(chat(pn2, "919944444444").getAssignedUserId()).isEqualTo(r.getId());
        assertThat(cursorOf(sales)).isEqualTo(r.getId());
    }

    @Test
    void roundRobinWithEveryoneAwayQueues() {
        User p = agent("p");
        Team sales = team("Sales", p);
        away(p);
        configure(sales, RoutingMode.ROUND_ROBIN, null);

        message("919911111111", false, T);

        WaWebChat c = chat("919911111111");
        assertThat(c.getAssignedUserId()).isNull();
        assertThat(c.getAssignedTeamId()).isEqualTo(sales.getId());
    }

    @Test
    void oneMemberModeFallsBackToQueueWhenAway() {
        User p = agent("p");
        Team sales = team("Sales", p);
        configure(sales, RoutingMode.USER, p.getId());
        away(p);

        message("919911111111", false, T);

        assertThat(chat("919911111111").getAssignedUserId()).isNull();
        assertThat(chat("919911111111").getAssignedTeamId()).isEqualTo(sales.getId());
    }

    @Test
    void stickyReturnsCustomerToLastAgentWithinWindow() {
        User p = agent("p");
        User r = agent("r");
        Team sales = team("Sales", p, r);
        configure(sales, RoutingMode.ROUND_ROBIN, null);
        message("919911111111", false, T);
        WaWebChat c = chat("919911111111");
        assertThat(c.getAssignedUserId()).isEqualTo(p.getId());
        lastHandledBy(c, p, java.time.Duration.ofDays(5));
        c = chat("919911111111");
        c.setStatus(ChatStatus.CLOSED);
        c.setAssignedUserId(null);
        c.setAssignedTeamId(null);
        chats.save(c);

        message("919911111111", false, T + 1);

        WaWebChat after = chat("919911111111");
        assertThat(after.getStatus()).isEqualTo(ChatStatus.OPEN);
        assertThat(after.getAssignedUserId()).isEqualTo(p.getId());
        assertThat(after.getAssignedTeamId()).isEqualTo(sales.getId());
        assertThat(lastRouted().getReason()).isEqualTo(RoutedReason.STICKY);
        // round robin did not move: the cursor still points at p from the first chat
        assertThat(cursorOf(sales)).isEqualTo(p.getId());
    }

    @Test
    void stickyExpiresAfterWindow() {
        User p = agent("p");
        User r = agent("r");
        Team sales = team("Sales", p, r);
        configure(sales, RoutingMode.ROUND_ROBIN, null);
        setCursor(sales, p.getId());
        message("919911111111", false, T);
        WaWebChat c = chat("919911111111");
        lastHandledBy(c, p, java.time.Duration.ofDays(31));
        c = chat("919911111111");
        c.setStatus(ChatStatus.CLOSED);
        c.setAssignedUserId(null);
        c.setAssignedTeamId(null);
        chats.save(c);

        message("919911111111", false, T + 1);

        assertThat(chat("919911111111").getAssignedUserId()).isEqualTo(p.getId()); // the rotation, not stickiness
        assertThat(lastRouted().getReason()).isNull();
    }

    @Test
    void stickySkipsIneligibleAgentAndKeepsTheLink() {
        User p = agent("p");
        User r = agent("r");
        Team sales = team("Sales", p, r);
        configure(sales, RoutingMode.ROUND_ROBIN, null);
        setCursor(sales, p.getId());
        message("919911111111", false, T);
        WaWebChat c = chat("919911111111");
        lastHandledBy(c, p, java.time.Duration.ofDays(1));
        c = chat("919911111111");
        c.setStatus(ChatStatus.CLOSED);
        c.setAssignedUserId(null);
        c.setAssignedTeamId(null);
        chats.save(c);
        away(p);

        message("919911111111", false, T + 1);

        WaWebChat after = chat("919911111111");
        assertThat(after.getAssignedUserId()).isEqualTo(r.getId());
        assertThat(after.getLastAgentUserId()).isEqualTo(p.getId());
        assertThat(lastRouted().getReason()).isNull();
    }

    @Test
    void stickyOffWhenZeroDays() {
        User p = agent("p");
        User r = agent("r");
        Team sales = team("Sales", p, r);
        configure(sales, RoutingMode.ROUND_ROBIN, null);
        settings.putOverride(brand.getId(), sales.getId(), new RoutingSetting.Request(null, null, null, 0, null, null));
        setCursor(sales, p.getId());
        WaWebChat c = new WaWebChat();
        c.setCustomerId(acme.getId());
        c.setWorkspaceId(brand.getId());
        c.setPhoneNumberId(pn);
        c.setRemoteId("919911111111@c.us");
        c.setStatus(ChatStatus.CLOSED);
        c = chats.save(c);
        lastHandledBy(c, p, java.time.Duration.ofDays(1));

        message("919911111111", false, T);

        assertThat(chat("919911111111").getAssignedUserId()).isEqualTo(r.getId());
    }

    @Test
    void stickyAppliesOnCampaignFirstReply() {
        User p = agent("p");
        User r = agent("r");
        Team sales = team("Sales", p, r);
        configure(sales, RoutingMode.ROUND_ROBIN, null);
        setCursor(sales, p.getId());
        WaWebChat c = new WaWebChat();
        c.setCustomerId(acme.getId());
        c.setWorkspaceId(brand.getId());
        c.setPhoneNumberId(pn);
        c.setRemoteId("919911111111@c.us");
        c.setStatus(ChatStatus.OPEN);
        c.setCampaignId("c-1");
        c = chats.save(c);
        lastHandledBy(c, p, java.time.Duration.ofDays(2));

        publisher.publishEvent(new InboxEvents.CampaignChatFirstReply(chats.findById(c.getId()).orElseThrow()));

        assertThat(chat("919911111111").getAssignedUserId()).isEqualTo(p.getId());
        assertThat(lastRouted().getReason()).isEqualTo(RoutedReason.STICKY);
    }

    @Test
    void excludeUserIdSkipsThatMemberAndSticky() {
        User p = agent("p");
        User r = agent("r");
        Team sales = team("Sales", p, r);
        configure(sales, RoutingMode.ROUND_ROBIN, null);
        setCursor(sales, r.getId()); // next in line would be p
        WaWebChat c = new WaWebChat();
        c.setCustomerId(acme.getId());
        c.setWorkspaceId(brand.getId());
        c.setPhoneNumberId(pn);
        c.setRemoteId("919911111111@c.us");
        c.setStatus(ChatStatus.OPEN);
        c = chats.save(c);
        lastHandledBy(c, p, java.time.Duration.ofDays(1));

        WaWebChat routed = routing.route(chats.findById(c.getId()).orElseThrow(), sales,
                new ChatRoutingService.RoutingOptions(p.getId(), false, RoutedReason.HANDED_BACK));

        assertThat(routed.getAssignedUserId()).isEqualTo(r.getId());
        assertThat(lastRouted().getReason()).isEqualTo(RoutedReason.HANDED_BACK);
        assertThat(lastRouted().getExcludedUserId()).isEqualTo(p.getId());

        // with r excluded too nobody is left: the queue
        WaWebChat again = routing.route(chats.findById(c.getId()).orElseThrow(), sales,
                new ChatRoutingService.RoutingOptions(r.getId(), false, RoutedReason.HANDED_BACK));
        assertThat(again.getAssignedUserId()).isEqualTo(p.getId()); // p is next after r and only r is excluded
    }
}
