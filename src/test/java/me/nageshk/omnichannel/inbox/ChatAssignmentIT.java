package me.nageshk.omnichannel.inbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import me.nageshk.omnichannel.access.AccessService;
import me.nageshk.omnichannel.access.BuiltInRoles;
import me.nageshk.omnichannel.api.ValidationException;
import me.nageshk.omnichannel.auth.AuthenticatedUser;
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
}
