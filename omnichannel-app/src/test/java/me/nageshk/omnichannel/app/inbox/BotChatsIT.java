package me.nageshk.omnichannel.app.inbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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
import me.nageshk.omnichannel.service.inbox.BotChats;
import me.nageshk.omnichannel.service.inbox.InboxSettingService;
import me.nageshk.omnichannel.service.inbox.RoutingSetting;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

/** Spec 2026-11-18 §5.3: a chatbot takes a chat from nobody and gives it to people or closes it. */
@SpringBootTest
class BotChatsIT {

    @Autowired BotChats botChats;
    @Autowired TestUsers testUsers;
    @Autowired WaWebChatRepository chats;
    @Autowired WaWebChatActionRepository actions;
    @Autowired TeamRepository teams;
    @Autowired PhoneNumberRepository phoneNumbers;
    @Autowired InboxSettingService settings;
    @Autowired MongoTemplate mongo;
    @Autowired java.time.Clock clock;

    Customer acme;
    Workspace brand;
    PhoneNumber number;

    @BeforeEach
    void setUp() {
        clean();
        acme = testUsers.customer("Acme");
        brand = testUsers.workspace(acme.getId(), "Brand A");
        number = testUsers.phoneNumber(acme.getId(), brand.getId(), "9199" + System.nanoTime() % 100000000);
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    private void clean() {
        testUsers.deleteAll();
        for (Class<?> c : List.of(WaWebChat.class, WaWebChatAction.class, InboxSetting.class)) mongo.remove(new Query(), c);
    }

    private User agent(String username) {
        return testUsers.createInCustomer(acme.getId(), username, null,
                List.of(new WorkspaceRole(brand.getId(), testUsers.roleId(BuiltInRoles.AGENT))));
    }

    /** A team owning the number, routing by {@code mode}. */
    private Team owner(RoutingMode mode, User... members) {
        Team t = new Team();
        t.setCustomerId(acme.getId());
        t.setWorkspaceId(brand.getId());
        t.setName("Support");
        t.setMemberIds(new ArrayList<>(java.util.Arrays.stream(members).map(User::getId).toList()));
        t = teams.save(t);
        number.setTeamId(t.getId());
        number = phoneNumbers.save(number);
        settings.putOverride(brand.getId(), t.getId(), new RoutingSetting.Request(mode != RoutingMode.NONE,
                mode == RoutingMode.ROUND_ROBIN, null, null, null, null));
        return t;
    }

    private WaWebChat chat(ChatStatus status, String userId, String teamId) {
        WaWebChat c = new WaWebChat();
        c.setCustomerId(acme.getId());
        c.setWorkspaceId(brand.getId());
        c.setPhoneNumberId(number.getId());
        c.setRemoteId(UUID.randomUUID() + "@c.us");
        c.setStatus(status);
        c.setAssignedUserId(userId);
        c.setAssignedTeamId(teamId);
        return chats.save(c);
    }

    private WaWebChat held(String runId) {
        WaWebChat c = chat(ChatStatus.BOT, null, null);
        c.setChatbot(new WaWebChat.ActiveChatbot(runId, "cb1", "Welcome", "Ask"));
        return chats.save(c);
    }

    private WaWebChat fresh(WaWebChat c) {
        return chats.findById(c.getId()).orElseThrow();
    }

    private List<WaWebChatAction> actionsOf(WaWebChat c, ActionType type) {
        return actions.findAll().stream().filter(a -> a.getChatId().equals(c.getId()) && a.getType() == type).toList();
    }

    @Test
    void takeTurnsAnOpenUnheldChatBotAndClearsItsTeam() {
        WaWebChat c = chat(ChatStatus.OPEN, null, "team-1");
        c.setHandedOverAt(clock.instant());
        c.setRoutingPendingSince(clock.instant());
        chats.save(c);

        assertThat(botChats.take(c.getId())).isTrue();

        WaWebChat after = fresh(c);
        assertThat(after.getStatus()).isEqualTo(ChatStatus.BOT);
        assertThat(after.getAssignedTeamId()).isNull();
        assertThat(after.getHandedOverAt()).isNull();
        assertThat(after.getRoutingPendingSince()).isNull();
    }

    @Test
    void takeLeavesAChatWithAnAgent() {
        User a = agent("a");
        WaWebChat c = chat(ChatStatus.OPEN, a.getId(), null);

        assertThat(botChats.take(c.getId())).isFalse();
        assertThat(fresh(c).getStatus()).isEqualTo(ChatStatus.OPEN);
        assertThat(fresh(c).getAssignedUserId()).isEqualTo(a.getId());
    }

    @Test
    void takeLeavesAClosedChat() {
        WaWebChat c = chat(ChatStatus.CLOSED, null, null);

        assertThat(botChats.take(c.getId())).isFalse();
        assertThat(fresh(c).getStatus()).isEqualTo(ChatStatus.CLOSED);
    }

    @Test
    void takeBumpsTheVersionSoAStaleSaveFails() {
        WaWebChat c = chat(ChatStatus.OPEN, null, null);
        WaWebChat stale = fresh(c);

        botChats.take(c.getId());

        assertThatThrownBy(() -> chats.save(stale)).isInstanceOf(OptimisticLockingFailureException.class);
    }

    @Test
    void openMakesABotChatOpenAndMarksTheHandOver() {
        WaWebChat c = held("r1");

        assertThat(botChats.open(c.getId())).isPresent();

        WaWebChat after = fresh(c);
        assertThat(after.getStatus()).isEqualTo(ChatStatus.OPEN);
        assertThat(after.getHandedOverAt()).isNotNull();
        assertThat(botChats.open(chat(ChatStatus.OPEN, null, null).getId())).isEmpty();
    }

    @Test
    void toPeopleRoutesByTheTeamsSetting() {
        User a = agent("a");
        Team support = owner(RoutingMode.ROUND_ROBIN, a);
        WaWebChat c = held("r1");

        botChats.toPeople(c.getId(), "r1", RoutedReason.CHATBOT_ENDED, "Welcome");

        WaWebChat after = fresh(c);
        assertThat(after.getStatus()).isEqualTo(ChatStatus.OPEN);
        assertThat(after.getAssignedUserId()).isEqualTo(a.getId());
        assertThat(after.getAssignedTeamId()).isEqualTo(support.getId());
        assertThat(after.getHandedOverAt()).isNotNull();
        List<WaWebChatAction> routed = actionsOf(c, ActionType.ROUTED);
        assertThat(routed).hasSize(1);
        assertThat(routed.get(0).getReason()).isEqualTo(RoutedReason.CHATBOT_ENDED);
        assertThat(routed.get(0).getByName()).isEqualTo("Chatbot: Welcome");
        assertThat(routed.get(0).getReasonDetail()).isEqualTo("Welcome");
    }

    @Test
    void toPeopleReturnsAStickyCustomerToTheirAgent() {
        User a = agent("a");
        User b = agent("b");
        owner(RoutingMode.ROUND_ROBIN, a, b);
        WaWebChat c = held("r1");
        c.setLastAgentUserId(b.getId());
        c.setLastAgentAt(clock.instant().minus(java.time.Duration.ofDays(1)));
        chats.save(c);

        botChats.toPeople(c.getId(), "r1", RoutedReason.CHATBOT_ENDED, "Welcome");

        assertThat(fresh(c).getAssignedUserId()).isEqualTo(b.getId());
        WaWebChatAction routed = actionsOf(c, ActionType.ROUTED).get(0);
        assertThat(routed.getReason()).isEqualTo(RoutedReason.STICKY);
        assertThat(routed.getByName()).isEqualTo("Chatbot: Welcome");
    }

    @Test
    void toPeopleWithoutAnOwningTeamLeavesItOpenAndUnassigned() {
        WaWebChat c = held("r1");

        botChats.toPeople(c.getId(), "r1", RoutedReason.CHATBOT_ENDED, "Welcome");

        WaWebChat after = fresh(c);
        assertThat(after.getStatus()).isEqualTo(ChatStatus.OPEN);
        assertThat(after.getAssignedUserId()).isNull();
        assertThat(after.getAssignedTeamId()).isNull();
        assertThat(actionsOf(c, ActionType.ROUTED)).isEmpty();
    }

    @Test
    void toPeopleWithRoutingNoneLeavesItOpenAndUnassigned() {
        owner(RoutingMode.NONE, agent("a"));
        WaWebChat c = held("r1");

        botChats.toPeople(c.getId(), "r1", RoutedReason.CHATBOT_ENDED, "Welcome");

        assertThat(fresh(c).getStatus()).isEqualTo(ChatStatus.OPEN);
        assertThat(fresh(c).getAssignedUserId()).isNull();
        assertThat(actionsOf(c, ActionType.ROUTED)).isEmpty();
    }

    @Test
    void toPeopleDoesNothingWhenAnotherRunHoldsTheChat() {
        owner(RoutingMode.ROUND_ROBIN, agent("a"));
        WaWebChat c = held("r2");

        botChats.toPeople(c.getId(), "r1", RoutedReason.CHATBOT_ENDED, "Welcome");

        assertThat(fresh(c).getStatus()).isEqualTo(ChatStatus.BOT);
        assertThat(actionsOf(c, ActionType.ROUTED)).isEmpty();
    }

    @Test
    void closeClosesWithAChatbotAction() {
        WaWebChat c = held("r1");

        botChats.close(c.getId(), "r1", "Welcome");

        assertThat(fresh(c).getStatus()).isEqualTo(ChatStatus.CLOSED);
        List<WaWebChatAction> closed = actionsOf(c, ActionType.CLOSED);
        assertThat(closed).hasSize(1);
        assertThat(closed.get(0).getByName()).isEqualTo("Chatbot: Welcome");
        assertThat(closed.get(0).getByUserId()).isNull();
    }

    @Test
    void markHandedOverStampsAChatThatWasNotBot() {
        WaWebChat c = chat(ChatStatus.OPEN, null, null);

        botChats.markHandedOver(c.getId());

        assertThat(fresh(c).getHandedOverAt()).isNotNull();
    }
}
