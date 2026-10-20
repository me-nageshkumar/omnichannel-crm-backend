package me.nageshk.omnichannel.inbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import me.nageshk.omnichannel.access.BuiltInRoles;
import me.nageshk.omnichannel.events.WhatsAppEvent;
import me.nageshk.omnichannel.support.TestUsers;
import me.nageshk.omnichannel.tenant.Customer;
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
    @Autowired InboxSettingService settings;
    @Autowired InboxSettingRepository settingRepo;
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

    private void configure(RoutingMode mode, String teamId, String userId) {
        settings.put(brand.getId(), pn,
                new InboxSettingService.SettingsRequest(new InboxSetting.Routing(mode, teamId, userId), null, null));
    }

    private void message(String digits, boolean fromMe, long epochSeconds) {
        ObjectNode p = mapper.createObjectNode();
        ObjectNode id = p.putObject("id");
        id.put("fromMe", fromMe);
        id.put("remote", digits + "@c.us");
        id.put("id", "RT" + epochSeconds);
        p.put("fromMe", fromMe);
        p.put("timestamp", epochSeconds);
        p.put("body", "hi");
        publisher.publishEvent(
                new WhatsAppEvent(this, pn, brand.getId(), acme.getId(), "message_create", p, null, null));
    }

    private WaWebChat chat(String digits) {
        return chats.findByPhoneNumberIdAndRemoteId(pn, digits + "@c.us").orElseThrow();
    }

    @Test
    void noneLeavesUnassigned() {
        message("919911111111", false, T);

        WaWebChat c = chat("919911111111");
        assertThat(c.getAssignedUserId()).isNull();
        assertThat(c.getAssignedTeamId()).isNull();
        assertThat(c.getAssignedAt()).isNull();
        assertThat(actions.findAll()).isEmpty();
    }

    @Test
    void teamModeQueuesOnTeam() {
        Team support = team("Support", agent("a"));
        configure(RoutingMode.TEAM, support.getId(), null);

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
        configure(RoutingMode.USER, null, a.getId());

        message("919911111111", false, T);

        WaWebChat c = chat("919911111111");
        assertThat(c.getAssignedUserId()).isEqualTo(a.getId());
        assertThat(c.getAssignedTeamId()).isNull();
        assertThat(actions.findAll()).singleElement().satisfies(x -> {
            assertThat(x.getType()).isEqualTo(ActionType.ROUTED);
            assertThat(x.getToUserId()).isEqualTo(a.getId());
            assertThat(x.getToUserName()).isEqualTo("Test a");
        });
    }

    @Test
    void userModeWithADisabledUserLeavesTheChatUnassigned() {
        User a = agent("a");
        configure(RoutingMode.USER, null, a.getId());
        a.setActive(false);
        users.save(a);

        message("919911111111", false, T);

        WaWebChat c = chat("919911111111");
        assertThat(c.getAssignedUserId()).isNull();
        assertThat(c.getAssignedTeamId()).isNull();
        assertThat(actions.findAll()).isEmpty();
    }

    @Test
    void userModeWithABrandDisabledOrUnreachableUserLeavesTheChatUnassigned() {
        User blocked = agent("blocked");
        User away = agent("away");
        configure(RoutingMode.USER, null, blocked.getId());
        blocked.setBrandDisables(List.of(new me.nageshk.omnichannel.user.BrandDisable(brand.getId(),
                new me.nageshk.omnichannel.enablement.Disabled("x",
                        me.nageshk.omnichannel.enablement.EnableLevel.COMPANY, java.time.Instant.now()))));
        users.save(blocked);
        message("919911111111", false, T);

        configure(RoutingMode.USER, null, away.getId());
        away.setWorkspaceRoles(List.of(new WorkspaceRole(other.getId(), testUsers.roleId(BuiltInRoles.AGENT))));
        users.save(away);
        message("919922222222", false, T + 1);

        assertThat(chat("919911111111").getAssignedUserId()).isNull();
        assertThat(chat("919922222222").getAssignedUserId()).isNull();
        assertThat(actions.findAll()).isEmpty();
    }

    @Test
    void userModeWithAnIneligibleUserQueuesOnTheConfiguredTeamOnly() {
        User a = agent("a");
        Team support = team("Support", a);
        a.setActive(false);
        users.save(a);
        InboxSetting setting = settings.get(brand.getId(), pn);
        setting.setCustomerId(acme.getId());
        setting.setWorkspaceId(brand.getId());
        setting.setRouting(new InboxSetting.Routing(RoutingMode.USER, support.getId(), a.getId()));
        settingRepo.save(setting);

        message("919911111111", false, T);

        WaWebChat c = chat("919911111111");
        assertThat(c.getAssignedUserId()).isNull();
        assertThat(c.getAssignedTeamId()).isEqualTo(support.getId());
    }

    @Test
    void roundRobinCursorWriteLeavesTheRestOfTheSettingAlone() {
        User a = agent("a");
        Team support = team("Support", a);
        configure(RoutingMode.ROUND_ROBIN, support.getId(), null);
        // the routing read a copy of the setting, then an admin saved a different one before the cursor moved
        InboxSetting stale = settingRepo.findByPhoneNumberId(pn).orElseThrow();
        InboxSettingService staleSettings = org.mockito.Mockito.mock(InboxSettingService.class);
        org.mockito.Mockito.when(staleSettings.get(brand.getId(), pn)).thenReturn(stale);
        settings.put(brand.getId(), pn, new InboxSettingService.SettingsRequest(
                new InboxSetting.Routing(RoutingMode.ROUND_ROBIN, support.getId(), null), false, true));
        ChatRoutingService routing = new ChatRoutingService(staleSettings, mongo, teams, users, userAdmin, chats,
                actions, publisher, clock);
        WaWebChat fresh = new WaWebChat();
        fresh.setCustomerId(acme.getId());
        fresh.setWorkspaceId(brand.getId());
        fresh.setPhoneNumberId(pn);
        fresh.setRemoteId("919911111111@c.us");
        fresh.setStatus(ChatStatus.OPEN);
        fresh = chats.save(fresh);

        routing.route(fresh);

        InboxSetting now = settingRepo.findByPhoneNumberId(pn).orElseThrow();
        assertThat(now.getRoundRobinCursor()).isEqualTo(a.getId());
        assertThat(now.isClaimOnReply()).isFalse();
        assertThat(now.isKeepAssignmentOnClose()).isTrue();
    }

    @Test
    void roundRobinRotatesAndSkipsDisabled() {
        User a = agent("a");
        User b = agent("b");
        User c = agent("c");
        Team support = team("Support", a, b, c);
        b.setActive(false);
        users.save(b);
        configure(RoutingMode.ROUND_ROBIN, support.getId(), null);

        message("919911111111", false, T);
        message("919922222222", false, T + 1);
        message("919933333333", false, T + 2);

        assertThat(chat("919911111111").getAssignedUserId()).isEqualTo(a.getId());
        assertThat(chat("919922222222").getAssignedUserId()).isEqualTo(c.getId());
        assertThat(chat("919933333333").getAssignedUserId()).isEqualTo(a.getId());
        assertThat(chat("919911111111").getAssignedTeamId()).isEqualTo(support.getId());
        assertThat(settingRepo.findByPhoneNumberId(pn).orElseThrow().getRoundRobinCursor()).isEqualTo(a.getId());
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
        configure(RoutingMode.ROUND_ROBIN, support.getId(), null);

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
        configure(RoutingMode.ROUND_ROBIN, support.getId(), null);

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
        configure(RoutingMode.USER, null, a.getId());

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
        configure(RoutingMode.USER, null, a.getId());

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
        configure(RoutingMode.USER, null, a.getId());
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
        configure(RoutingMode.USER, null, a.getId());

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
        configure(RoutingMode.ROUND_ROBIN, support.getId(), null);
        InboxSetting s = settingRepo.findByPhoneNumberId(pn).orElseThrow();
        s.setRoundRobinCursor(gone.getId());
        settingRepo.save(s);

        message("919911111111", false, T);

        assertThat(chat("919911111111").getAssignedUserId()).isEqualTo(a.getId());
        assertThat(settingRepo.findByPhoneNumberId(pn).orElseThrow().getRoundRobinCursor()).isEqualTo(a.getId());
    }

    @Test
    void roundRobinCursorStaysWhenNoMemberIsEligible() {
        User a = agent("a");
        User b = agent("b");
        Team support = team("Support", a, b);
        configure(RoutingMode.ROUND_ROBIN, support.getId(), null);
        InboxSetting s = settingRepo.findByPhoneNumberId(pn).orElseThrow();
        s.setRoundRobinCursor(a.getId());
        settingRepo.save(s);
        for (User u : List.of(a, b)) {
            u.setActive(false);
            users.save(u);
        }

        message("919911111111", false, T);

        assertThat(chat("919911111111").getAssignedTeamId()).isEqualTo(support.getId());
        assertThat(settingRepo.findByPhoneNumberId(pn).orElseThrow().getRoundRobinCursor()).isEqualTo(a.getId());
    }

    @Test
    void teamModeWithADeletedTeamLeavesTheChatUntouched() {
        Team support = team("Support", agent("a"));
        configure(RoutingMode.TEAM, support.getId(), null);
        teams.deleteById(support.getId());

        message("919911111111", false, T);

        WaWebChat c = chat("919911111111");
        assertThat(c.getAssignedTeamId()).isNull();
        assertThat(c.getAssignedUserId()).isNull();
        assertThat(actions.findAll()).isEmpty();
    }

    @Test
    void userModeWithADeletedUserLeavesTheChatUntouched() {
        User a = agent("a");
        configure(RoutingMode.USER, null, a.getId());
        users.deleteById(a.getId());

        message("919911111111", false, T);

        WaWebChat c = chat("919911111111");
        assertThat(c.getAssignedUserId()).isNull();
        assertThat(actions.findAll()).isEmpty();
    }
}
