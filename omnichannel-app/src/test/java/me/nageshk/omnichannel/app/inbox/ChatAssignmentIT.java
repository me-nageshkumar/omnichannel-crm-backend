package me.nageshk.omnichannel.app.inbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import me.nageshk.omnichannel.app.support.TestUsers;
import me.nageshk.omnichannel.common.api.NotFoundException;
import me.nageshk.omnichannel.common.api.ValidationException;
import me.nageshk.omnichannel.common.inbox.ChatTakenException;
import me.nageshk.omnichannel.db.inbox.ActionType;
import me.nageshk.omnichannel.db.inbox.ChatStatus;
import me.nageshk.omnichannel.db.inbox.InboxSetting;
import me.nageshk.omnichannel.db.inbox.RoutedReason;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import me.nageshk.omnichannel.db.inbox.WaWebChatAction;
import me.nageshk.omnichannel.db.inbox.WaWebChatActionRepository;
import me.nageshk.omnichannel.db.inbox.WaWebChatRepository;
import me.nageshk.omnichannel.db.tenant.Customer;
import me.nageshk.omnichannel.db.tenant.Team;
import me.nageshk.omnichannel.db.tenant.TeamRepository;
import me.nageshk.omnichannel.db.tenant.Workspace;
import me.nageshk.omnichannel.db.user.User;
import me.nageshk.omnichannel.db.user.WorkspaceRole;
import me.nageshk.omnichannel.security.access.AccessService;
import me.nageshk.omnichannel.security.access.BuiltInRoles;
import me.nageshk.omnichannel.security.auth.AuthenticatedUser;
import me.nageshk.omnichannel.service.inbox.ChatAssignmentService;
import me.nageshk.omnichannel.service.inbox.HandoffRequest;
import me.nageshk.omnichannel.service.inbox.InboxEvents;
import me.nageshk.omnichannel.service.inbox.InboxSettingService;
import me.nageshk.omnichannel.service.inbox.RoutingSetting;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

@SpringBootTest
class ChatAssignmentIT {


    static class Recorder {
        final List<Object> events = new CopyOnWriteArrayList<>();

        @EventListener
        void on(InboxEvents.ActionRecorded e) {
            events.add(e);
        }

        @EventListener
        void on(InboxEvents.ChatChanged e) {
            events.add(e);
        }
    }

    @TestConfiguration
    static class RecorderConfig {
        @Bean
        Recorder assignmentRecorder() {
            return new Recorder();
        }
    }

    @Autowired ChatAssignmentService assignment;
    @Autowired InboxSettingService settings;
    @Autowired me.nageshk.omnichannel.db.phone.PhoneNumberRepository phoneNumbers;
    @Autowired WaWebChatRepository chats;
    @Autowired WaWebChatActionRepository actions;
    @Autowired TeamRepository teams;
    @Autowired AccessService access;
    @Autowired TestUsers testUsers;
    @Autowired MongoTemplate mongo;
    @Autowired Recorder recorder;

    Customer acme;
    Workspace brand;
    Workspace other;
    String pn;
    User manager;
    AuthenticatedUser me;

    @BeforeEach
    void setUp() {
        clean();
        acme = testUsers.customer("Acme");
        brand = testUsers.workspace(acme.getId(), "Brand A");
        other = testUsers.workspace(acme.getId(), "Brand B");
        pn = testUsers.phoneNumber(acme.getId(), brand.getId(), "9199000" + System.nanoTime() % 100000).getId();
        manager = user("manager", brand);
        me = access.load(manager);
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    private void clean() {
        testUsers.deleteAll();
        for (Class<?> c : List.of(WaWebChat.class, WaWebChatAction.class, InboxSetting.class))
            mongo.remove(new Query(), c);
        recorder.events.clear();
    }

    private User user(String username, Workspace w) {
        return testUsers.createInCustomer(acme.getId(), username, null,
                List.of(new WorkspaceRole(w.getId(), testUsers.roleId(BuiltInRoles.AGENT))));
    }

    private Team team(String name, User... members) {
        Team t = new Team();
        t.setCustomerId(acme.getId());
        t.setWorkspaceId(brand.getId());
        t.setName(name);
        t.setMemberIds(new ArrayList<>(java.util.Arrays.stream(members).map(User::getId).toList()));
        return teams.save(t);
    }

    private WaWebChat chat() {
        WaWebChat c = new WaWebChat();
        c.setCustomerId(acme.getId());
        c.setWorkspaceId(brand.getId());
        c.setPhoneNumberId(pn);
        c.setRemoteId("919911111111@c.us");
        c.setStatus(ChatStatus.OPEN);
        return chats.save(c);
    }

    private void configure(boolean claimOnReply, boolean keepAssignmentOnClose) {
        settings.putDefault(brand.getId(),
                new RoutingSetting.Request(false, false, null, null, claimOnReply, keepAssignmentOnClose));
    }

    @Test
    void assignToUserRecordsAction() {
        User agent = user("agent", brand);
        WaWebChat c = chat();

        WaWebChat result = assignment.assign(me, c, agent.getId(), null);

        assertThat(result.getAssignedUserId()).isEqualTo(agent.getId());
        assertThat(result.getAssignedAt()).isNotNull();
        WaWebChat stored = chats.findById(c.getId()).orElseThrow();
        assertThat(stored.getAssignedUserId()).isEqualTo(agent.getId());
        assertThat(actions.findAll()).singleElement().satisfies(a -> {
            assertThat(a.getType()).isEqualTo(ActionType.ASSIGNED);
            assertThat(a.getByUserId()).isEqualTo(manager.getId());
            assertThat(a.getByName()).isEqualTo("Test manager");
            assertThat(a.getToUserId()).isEqualTo(agent.getId());
            assertThat(a.getToUserName()).isEqualTo("Test agent");
            assertThat(a.getToTeamId()).isNull();
            assertThat(a.getChatId()).isEqualTo(c.getId());
        });
        assertThat(recorder.events).hasSize(2);
        assertThat(recorder.events).anyMatch(e -> e instanceof InboxEvents.ChatChanged);
        assertThat(recorder.events).anyMatch(e -> e instanceof InboxEvents.ActionRecorded);
    }

    @Test
    void assignToTeamAndUserKeepsBothWhenMember() {
        User agent = user("agent", brand);
        Team support = team("Support", agent);

        WaWebChat result = assignment.assign(me, chat(), agent.getId(), support.getId());

        assertThat(result.getAssignedUserId()).isEqualTo(agent.getId());
        assertThat(result.getAssignedTeamId()).isEqualTo(support.getId());
        assertThat(actions.findAll()).singleElement().satisfies(a -> {
            assertThat(a.getToUserName()).isEqualTo("Test agent");
            assertThat(a.getToTeamName()).isEqualTo("Support");
        });
    }

    @Test
    void assignToTeamClearsNonMemberUser() {
        User agent = user("agent", brand);
        User member = user("member", brand);
        Team support = team("Support", member);
        WaWebChat c = chat();
        assignment.assign(me, c, agent.getId(), null);

        WaWebChat result = assignment.assign(me, chats.findById(c.getId()).orElseThrow(), null, support.getId());

        assertThat(result.getAssignedUserId()).isNull();
        assertThat(result.getAssignedTeamId()).isEqualTo(support.getId());
        assertThat(actions.findAll()).filteredOn(a -> a.getToTeamId() != null).singleElement().satisfies(a -> {
            assertThat(a.getToUserId()).isNull();
            assertThat(a.getToTeamName()).isEqualTo("Support");
        });
    }

    @Test
    void assignRejectsUserWithoutAccess() {
        User outsider = user("outsider", other);

        assertThatThrownBy(() -> assignment.assign(me, chat(), outsider.getId(), null))
                .isInstanceOfSatisfying(ValidationException.class,
                        e -> assertThat(e.fields()).containsEntry("userId", "unknown user"));
        assertThat(actions.findAll()).isEmpty();
    }

    @Test
    void assignRejectsTeamOfAnotherWorkspaceAndEmptyTarget() {
        Team foreign = new Team();
        foreign.setCustomerId(acme.getId());
        foreign.setWorkspaceId(other.getId());
        foreign.setName("Elsewhere");
        foreign = teams.save(foreign);
        String foreignId = foreign.getId();
        WaWebChat c = chat();

        assertThatThrownBy(() -> assignment.assign(me, c, null, foreignId))
                .isInstanceOfSatisfying(ValidationException.class,
                        e -> assertThat(e.fields()).containsEntry("teamId", "unknown team"));
        assertThatThrownBy(() -> assignment.assign(me, c, null, null))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void unassignClearsBoth() {
        User agent = user("agent", brand);
        Team support = team("Support", agent);
        WaWebChat c = assignment.assign(me, chat(), agent.getId(), support.getId());

        WaWebChat result = assignment.unassign(me, c);

        assertThat(result.getAssignedUserId()).isNull();
        assertThat(result.getAssignedTeamId()).isNull();
        assertThat(actions.findAll()).extracting(WaWebChatAction::getType)
                .containsExactlyInAnyOrder(ActionType.ASSIGNED, ActionType.UNASSIGNED);
    }

    @Test
    void closeClearsAssignmentByDefault() {
        User agent = user("agent", brand);
        WaWebChat c = assignment.assign(me, chat(), agent.getId(), null);

        WaWebChat result = assignment.close(me, c);

        assertThat(result.getStatus()).isEqualTo(ChatStatus.CLOSED);
        assertThat(result.getAssignedUserId()).isNull();
        assertThat(result.getAssignedTeamId()).isNull();
        assertThat(chats.findById(c.getId()).orElseThrow().getStatus()).isEqualTo(ChatStatus.CLOSED);
        assertThat(actions.findAll()).anySatisfy(a -> {
            assertThat(a.getType()).isEqualTo(ActionType.CLOSED);
            assertThat(a.getByUserId()).isEqualTo(manager.getId());
        });
    }

    @Test
    void closeKeepsAssignmentWhenSettingSaysSo() {
        configure(true, true);
        User agent = user("agent", brand);
        WaWebChat c = assignment.assign(me, chat(), agent.getId(), null);

        WaWebChat result = assignment.close(me, c);

        assertThat(result.getStatus()).isEqualTo(ChatStatus.CLOSED);
        assertThat(result.getAssignedUserId()).isEqualTo(agent.getId());
    }

    @Test
    void reopenKeepsAssignmentAndRecordsAction() {
        configure(true, true);
        User agent = user("agent", brand);
        WaWebChat closed = assignment.close(me, assignment.assign(me, chat(), agent.getId(), null));

        WaWebChat result = assignment.reopen(me, closed);

        assertThat(result.getStatus()).isEqualTo(ChatStatus.OPEN);
        assertThat(result.getAssignedUserId()).isEqualTo(agent.getId());
        assertThat(actions.findAll()).anySatisfy(a -> {
            assertThat(a.getType()).isEqualTo(ActionType.REOPENED);
            assertThat(a.getByUserId()).isEqualTo(manager.getId());
        });
    }

    @Test
    void claimOnReplyAssignsUnassignedOnly() {
        User agent = user("agent", brand);
        WaWebChat unassigned = chat();

        WaWebChat claimed = assignment.claimOnReply(me, unassigned);

        assertThat(claimed.getAssignedUserId()).isEqualTo(manager.getId());
        assertThat(actions.findAll()).singleElement().satisfies(a -> {
            assertThat(a.getType()).isEqualTo(ActionType.ASSIGNED);
            assertThat(a.getByUserId()).isEqualTo(manager.getId());
            assertThat(a.getToUserId()).isEqualTo(manager.getId());
        });

        WaWebChat taken = assignment.assign(me, chats.findById(unassigned.getId()).orElseThrow(), agent.getId(), null);
        long before = actions.count();
        WaWebChat after = assignment.claimOnReply(me, taken);

        assertThat(after.getAssignedUserId()).isEqualTo(agent.getId());
        assertThat(actions.count()).isEqualTo(before);
    }

    @Test
    void claimOnReplyDoesNothingWhenSettingIsOff() {
        configure(false, false);

        WaWebChat result = assignment.claimOnReply(me, chat());

        assertThat(result.getAssignedUserId()).isNull();
        assertThat(actions.findAll()).isEmpty();
    }

    @Test
    void userOnlyAssignKeepsTeamWhenUserIsMember() {
        User agent = user("agent", brand);
        Team support = team("Support", agent);
        WaWebChat c = assignment.assign(me, chat(), null, support.getId());

        WaWebChat result = assignment.assign(me, c, agent.getId(), null);

        assertThat(result.getAssignedUserId()).isEqualTo(agent.getId());
        assertThat(result.getAssignedTeamId()).isEqualTo(support.getId());
        assertThat(actions.findAll()).anySatisfy(a -> {
            assertThat(a.getToUserId()).isEqualTo(agent.getId());
            assertThat(a.getToTeamId()).isEqualTo(support.getId());
            assertThat(a.getToTeamName()).isEqualTo("Support");
        });
    }

    @Test
    void userOnlyAssignClearsTeamWhenUserIsNotMember() {
        User agent = user("agent", brand);
        User member = user("member", brand);
        Team support = team("Support", member);
        WaWebChat c = assignment.assign(me, chat(), null, support.getId());

        WaWebChat result = assignment.assign(me, c, agent.getId(), null);

        assertThat(result.getAssignedUserId()).isEqualTo(agent.getId());
        assertThat(result.getAssignedTeamId()).isNull();
    }

    @Test
    void claimOnReplyKeepsTheQueueTeam() {
        Team support = team("Support", manager);
        WaWebChat c = assignment.assign(me, chat(), null, support.getId());

        WaWebChat claimed = assignment.claimOnReply(me, c);

        assertThat(claimed.getAssignedUserId()).isEqualTo(manager.getId());
        assertThat(claimed.getAssignedTeamId()).isEqualTo(support.getId());
        assertThat(chats.findById(c.getId()).orElseThrow().getAssignedTeamId()).isEqualTo(support.getId());
        assertThat(actions.findAll()).anySatisfy(a -> {
            assertThat(a.getType()).isEqualTo(ActionType.ASSIGNED);
            assertThat(a.getToUserId()).isEqualTo(manager.getId());
            assertThat(a.getToTeamId()).isEqualTo(support.getId());
            assertThat(a.getToTeamName()).isEqualTo("Support");
        });
    }

    @Test
    void assignToUserSetsLastAgent() {
        User agent = user("agent", brand);
        WaWebChat c = chat();
        assertThat(c.getLastAgentUserId()).isNull();

        WaWebChat result = assignment.assign(me, c, agent.getId(), null);

        assertThat(result.getLastAgentUserId()).isEqualTo(agent.getId());
        assertThat(result.getLastAgentAt()).isNotNull();
        WaWebChat cleared = assignment.unassign(me, result);
        assertThat(cleared.getLastAgentUserId()).isEqualTo(agent.getId()); // never cleared
    }

    @Test
    void claimOnReplySetsLastAgent() {
        configure(true, false);
        WaWebChat result = assignment.claimOnReply(me, chat());
        assertThat(result.getAssignedUserId()).isEqualTo(manager.getId());
        assertThat(result.getLastAgentUserId()).isEqualTo(manager.getId());
    }

    // --- claim and hand-offs (design §3.5, §3.6) ---------------------------------------------------------------

    private Team owning(String name, User... members) {
        Team t = team(name, members);
        me.nageshk.omnichannel.db.phone.PhoneNumber number = phoneNumbers.findById(pn).orElseThrow();
        number.setTeamId(t.getId());
        phoneNumbers.save(number);
        return t;
    }

    private void roundRobin(Team t) {
        settings.putOverride(brand.getId(), t.getId(), new RoutingSetting.Request(true, true, null, null, null, null));
    }

    private WaWebChat chatOf(User user, Team team) {
        WaWebChat c = chat();
        c.setAssignedUserId(user == null ? null : user.getId());
        c.setAssignedTeamId(team == null ? null : team.getId());
        return chats.save(c);
    }

    /** A second chat of the number, with its own remote id. */
    private WaWebChat anotherChatOf(User user, Team team) {
        WaWebChat c = new WaWebChat();
        c.setCustomerId(acme.getId());
        c.setWorkspaceId(brand.getId());
        c.setPhoneNumberId(pn);
        c.setRemoteId("919922222222@c.us");
        c.setStatus(ChatStatus.OPEN);
        c.setAssignedUserId(user == null ? null : user.getId());
        c.setAssignedTeamId(team == null ? null : team.getId());
        return chats.save(c);
    }

    private static HandoffRequest toUser(String userId) {
        return new HandoffRequest(HandoffRequest.Kind.TO_USER, userId, null);
    }

    private static HandoffRequest toTeam(String teamId) {
        return new HandoffRequest(HandoffRequest.Kind.TO_TEAM, null, teamId);
    }

    @Test
    void claimTakesUnassignedChatAndSetsLastAgent() {
        Team support = owning("Support", manager);
        WaWebChat c = chatOf(null, support);

        WaWebChat result = assignment.claim(me, c);

        assertThat(result.getAssignedUserId()).isEqualTo(manager.getId());
        assertThat(result.getAssignedTeamId()).isEqualTo(support.getId());
        assertThat(result.getLastAgentUserId()).isEqualTo(manager.getId());
        assertThat(actions.findAll()).singleElement().satisfies(a -> {
            assertThat(a.getType()).isEqualTo(ActionType.ASSIGNED);
            assertThat(a.getByUserId()).isEqualTo(manager.getId());
            assertThat(a.getToUserId()).isEqualTo(manager.getId());
            assertThat(a.getToTeamName()).isEqualTo("Support");
        });
    }

    @Test
    void claimOfTakenChatIs409() {
        User agent = user("agent", brand);
        WaWebChat c = chatOf(agent, null);

        assertThatThrownBy(() -> assignment.claim(me, c)).isInstanceOf(ChatTakenException.class);
        assertThat(chats.findById(c.getId()).orElseThrow().getAssignedUserId()).isEqualTo(agent.getId());
    }

    @Test
    void handoffToTeammateAssigns() {
        User agent = user("agent", brand);
        Team support = owning("Support", manager, agent);
        WaWebChat c = chatOf(manager, support);

        WaWebChat result = assignment.handoff(me, c, toUser(agent.getId()), false);

        assertThat(result.getAssignedUserId()).isEqualTo(agent.getId());
        assertThat(result.getAssignedTeamId()).isEqualTo(support.getId());
        assertThat(result.getLastAgentUserId()).isEqualTo(agent.getId());
        assertThat(actions.findAll()).singleElement().satisfies(a -> {
            assertThat(a.getType()).isEqualTo(ActionType.ASSIGNED);
            assertThat(a.getByUserId()).isEqualTo(manager.getId());
            assertThat(a.getToUserId()).isEqualTo(agent.getId());
        });
    }

    @Test
    void handoffToNonMemberIs422() {
        User outsider = user("outsider", brand);
        Team support = owning("Support", manager);
        WaWebChat c = chatOf(manager, support);

        assertThatThrownBy(() -> assignment.handoff(me, c, toUser(outsider.getId()), false))
                .isInstanceOfSatisfying(ValidationException.class,
                        e -> assertThat(e.fields()).containsKey("userId"));
        assertThat(chats.findById(c.getId()).orElseThrow().getAssignedUserId()).isEqualTo(manager.getId());
    }

    @Test
    void handBackRoutesToAnotherMember() {
        User agent = user("agent", brand);
        Team support = owning("Support", manager, agent);
        roundRobin(support);
        WaWebChat c = chatOf(manager, support);

        WaWebChat result = assignment.handoff(me, c, toTeam(null), false);

        assertThat(result.getAssignedUserId()).isEqualTo(agent.getId());
        assertThat(result.getAssignedTeamId()).isEqualTo(support.getId());
        assertThat(actions.findAll()).singleElement().satisfies(a -> {
            assertThat(a.getType()).isEqualTo(ActionType.ROUTED);
            assertThat(a.getReason()).isEqualTo(RoutedReason.HANDED_BACK);
            assertThat(a.getExcludedUserId()).isEqualTo(manager.getId());
        });
    }

    @Test
    void handBackWithNobodyElseQueues() {
        Team support = owning("Support", manager);
        roundRobin(support);
        WaWebChat c = chatOf(manager, support);

        WaWebChat result = assignment.handoff(me, c, toTeam(support.getId()), false);

        assertThat(result.getAssignedUserId()).isNull();
        assertThat(result.getAssignedTeamId()).isEqualTo(support.getId());
        assertThat(actions.findAll()).singleElement()
                .satisfies(a -> assertThat(a.getReason()).isEqualTo(RoutedReason.HANDED_BACK));
    }

    @Test
    void handBackSkipsSticky() {
        User agent = user("agent", brand);
        Team support = owning("Support", manager, agent);
        roundRobin(support);
        WaWebChat c = chatOf(manager, support);
        c.setLastAgentUserId(manager.getId());
        c.setLastAgentAt(java.time.Instant.now());
        c = chats.save(c);

        WaWebChat result = assignment.handoff(me, c, toTeam(null), false);

        assertThat(result.getAssignedUserId()).isEqualTo(agent.getId());
    }

    @Test
    void handBackOnATeamWithRoutingOffJustUnassigns() {
        Team support = owning("Support", manager);
        settings.putOverride(brand.getId(), support.getId(),
                new RoutingSetting.Request(false, false, null, null, null, null));
        WaWebChat c = chatOf(manager, support);

        WaWebChat result = assignment.handoff(me, c, toTeam(null), false);

        assertThat(result.getAssignedUserId()).isNull();
        assertThat(result.getAssignedTeamId()).isEqualTo(support.getId());
        assertThat(actions.findAll()).singleElement()
                .satisfies(a -> assertThat(a.getType()).isEqualTo(ActionType.UNASSIGNED));
    }

    @Test
    void transferRecordsAndRoutesPerTargetSetting() {
        User agent = user("agent", brand);
        Team support = owning("Support", manager);
        Team sales = team("Sales", agent);
        WaWebChat c = chatOf(manager, support);

        WaWebChat queued = assignment.handoff(me, c, toTeam(sales.getId()), false);

        assertThat(queued.getAssignedTeamId()).isEqualTo(sales.getId());
        assertThat(queued.getAssignedUserId()).isNull(); // brand default: team queue
        assertThat(actions.findAll()).extracting(WaWebChatAction::getType)
                .containsExactly(ActionType.TRANSFERRED, ActionType.ROUTED);
        WaWebChatAction transferred = actions.findAll().get(0);
        assertThat(transferred.getFromTeamId()).isEqualTo(support.getId());
        assertThat(transferred.getFromTeamName()).isEqualTo("Support");
        assertThat(transferred.getToTeamId()).isEqualTo(sales.getId());
        assertThat(transferred.getByUserId()).isEqualTo(manager.getId());

        // with round robin on the target, the chat lands on a member right away
        roundRobin(sales);
        WaWebChat other = anotherChatOf(manager, support);
        WaWebChat assigned = assignment.handoff(me, other, toTeam(sales.getId()), false);
        assertThat(assigned.getAssignedTeamId()).isEqualTo(sales.getId());
        assertThat(assigned.getAssignedUserId()).isEqualTo(agent.getId());
    }

    @Test
    void transferToUnknownTeamIs422() {
        Team support = owning("Support", manager);
        WaWebChat c = chatOf(manager, support);
        assertThatThrownBy(() -> assignment.handoff(me, c, toTeam("nope"), false))
                .isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.fields()).containsKey("teamId"));
    }

    @Test
    void handoffOfSomeoneElsesChatIs404ForAgentButNotForManager() {
        User agent = user("agent", brand);
        Team support = owning("Support", manager, agent);
        WaWebChat c = chatOf(agent, support);

        assertThatThrownBy(() -> assignment.handoff(me, c, toUser(manager.getId()), false))
                .isInstanceOf(NotFoundException.class);
        WaWebChat result = assignment.handoff(me, c, toUser(manager.getId()), true);
        assertThat(result.getAssignedUserId()).isEqualTo(manager.getId());
    }

    @Test
    void assignToUserWhoCannotSeeNumberIs422() {
        User agent = user("agent", brand);
        owning("Support", manager);
        WaWebChat c = chat();

        assertThatThrownBy(() -> assignment.assign(me, c, agent.getId(), null))
                .isInstanceOf(me.nageshk.omnichannel.common.api.AssigneeCannotSeeNumberException.class);
        // a member may be assigned, and so may someone who sees every number
        User boss = testUsers.createInCustomer(acme.getId(), "boss", null,
                List.of(new WorkspaceRole(brand.getId(), testUsers.roleId(BuiltInRoles.WORKSPACE_MANAGER))));
        assertThat(assignment.assign(me, c, manager.getId(), null).getAssignedUserId()).isEqualTo(manager.getId());
        assertThat(assignment.assign(me, c, boss.getId(), null).getAssignedUserId()).isEqualTo(boss.getId());
    }
}
