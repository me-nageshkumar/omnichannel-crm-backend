package me.nageshk.omnichannel.inbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import me.nageshk.omnichannel.contact.Contact;
import me.nageshk.omnichannel.contact.ContactRepository;
import me.nageshk.omnichannel.support.TestUsers;
import me.nageshk.omnichannel.tenant.Customer;
import me.nageshk.omnichannel.tenant.Team;
import me.nageshk.omnichannel.tenant.TeamRepository;
import me.nageshk.omnichannel.tenant.Workspace;
import me.nageshk.omnichannel.user.User;
import me.nageshk.omnichannel.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class InboxViewsIT {

    @Autowired InboxViews views;
    @Autowired ContactRepository contacts;
    @Autowired UserRepository users;
    @Autowired TeamRepository teams;
    @Autowired TestUsers testUsers;

    Customer acme;
    Workspace brand;

    @BeforeEach
    void setUp() {
        testUsers.deleteAll();
        contacts.deleteAll();
        acme = testUsers.customer("Acme");
        brand = testUsers.workspace(acme.getId(), "Brand A");
    }

    @AfterEach
    void cleanUp() {
        contacts.deleteAll();
        testUsers.deleteAll();
    }

    private Contact contact(String name, String phone) {
        Contact c = new Contact();
        c.setWorkspaceId(brand.getId());
        c.setCustomerId(acme.getId());
        c.setName(name);
        c.setPhone(phone);
        c.setWaId(phone == null ? "lid-" + name : phone);
        return contacts.save(c);
    }

    private Team team(String name) {
        Team t = new Team();
        t.setCustomerId(acme.getId());
        t.setWorkspaceId(brand.getId());
        t.setName(name);
        return teams.save(t);
    }

    private WaWebChat chat(String id, Contact c, String userId, String teamId) {
        WaWebChat chat = new WaWebChat();
        chat.setId(id);
        chat.setPhoneNumberId("pn1");
        chat.setRemoteId(id + "@c.us");
        chat.setContactId(c.getId());
        chat.setStatus(ChatStatus.OPEN);
        chat.setAssignedUserId(userId);
        chat.setAssignedTeamId(teamId);
        chat.setUnreadCount(3);
        chat.setLastMessageAt(Instant.parse("2026-01-01T10:00:00Z"));
        chat.setLastMessage(new WaWebChat.LastMessage(MessageDirection.IN, "chat", "hello", MessageStatus.SENT));
        return chat;
    }

    @Test
    void chatsResolvesContactUserAndTeamInBatch() {
        Contact withPhone = contact("Asha", "919900000001");
        Contact hidden = contact("Lid Person", null);
        User agent = testUsers.createInCustomer(acme.getId(), "agent", null, List.of());
        agent.setName("Agent Smith");
        users.save(agent);
        Team support = team("Support");

        List<ChatView> out = views.chats(List.of(
                chat("c1", withPhone, agent.getId(), support.getId()),
                chat("c2", hidden, null, null)));

        assertThat(out).hasSize(2);
        ChatView first = out.get(0);
        assertThat(first.id()).isEqualTo("c1");
        assertThat(first.contact()).isEqualTo(new ChatView.Contact(withPhone.getId(), "Asha", "919900000001", false));
        assertThat(first.assignedUser()).isEqualTo(new ChatView.Ref(agent.getId(), "Agent Smith"));
        assertThat(first.assignedTeam()).isEqualTo(new ChatView.Ref(support.getId(), "Support"));
        assertThat(first.unreadCount()).isEqualTo(3);
        assertThat(first.lastMessage()).isEqualTo(new ChatView.LastMessage(MessageDirection.IN, "chat", "hello", MessageStatus.SENT));
        ChatView second = out.get(1);
        assertThat(second.contact().numberHidden()).isTrue();
        assertThat(second.contact().phone()).isNull();
        assertThat(second.assignedUser()).isNull();
        assertThat(second.assignedTeam()).isNull();
    }

    @Test
    void deletedUserAndTeamResolveToNameNull() {
        Contact c = contact("Asha", "919900000001");

        ChatView view = views.chat(chat("c1", c, "gone-user", "gone-team"));

        assertThat(view.assignedUser()).isEqualTo(new ChatView.Ref("gone-user", null));
        assertThat(view.assignedTeam()).isEqualTo(new ChatView.Ref("gone-team", null));
    }

    @Test
    void deletedContactIsNullSafe() {
        Contact c = new Contact();
        c.setId("missing");

        ChatView view = views.chat(chat("c1", c, null, null));

        assertThat(view.contact().id()).isEqualTo("missing");
        assertThat(view.contact().name()).isNull();
        assertThat(view.contact().numberHidden()).isTrue();
    }

    @Test
    void mediaIsAvailableOnlyOnceCached() {
        WaWebMessage pending = message();
        pending.setMedia(new WaWebMessage.Media("image/jpeg", "a.jpg", 10L, null, null));
        WaWebMessage cached = message();
        cached.setMedia(new WaWebMessage.Media("image/jpeg", "a.jpg", 10L, "gridfs1", Instant.now()));
        WaWebMessage text = message();

        assertThat(views.message(pending).media().available()).isFalse();
        assertThat(views.message(cached).media().available()).isTrue();
        assertThat(views.message(cached).media().mimetype()).isEqualTo("image/jpeg");
        assertThat(views.message(text).media()).isNull();
        assertThat(views.message(text).location()).isNull();
    }

    @Test
    void messageAndActionViewsCopyEntityFields() {
        WaWebMessage m = message();
        m.setSentByUserId("u1");
        m.setSentByName("Agent");
        m.setLocation(new WaWebMessage.Location(1.5, 2.5, "Here"));
        m.setStatusTimes(new WaWebMessage.StatusTimes());
        MessageView mv = views.message(m);
        assertThat(mv.sentBy()).isEqualTo(new ChatView.Ref("u1", "Agent"));
        assertThat(mv.location()).isEqualTo(new MessageView.Location(1.5, 2.5, "Here"));
        assertThat(mv.statusTimes()).isSameAs(m.getStatusTimes());
        assertThat(mv.chatId()).isEqualTo("c1");

        WaWebChatAction a = new WaWebChatAction();
        a.setId("a1");
        a.setType(ActionType.ASSIGNED);
        a.setByUserId("u1");
        a.setByName("Boss");
        a.setToUserId("u2");
        a.setToUserName("Agent");
        a.setAt(Instant.parse("2026-01-01T10:00:00Z"));
        ActionView av = views.action(a);
        assertThat(av.by()).isEqualTo(new ChatView.Ref("u1", "Boss"));
        assertThat(av.toUser()).isEqualTo(new ChatView.Ref("u2", "Agent"));
        assertThat(av.toTeam()).isNull();
        assertThat(views.action(routed()).by()).isNull();
    }

    private static WaWebChatAction routed() {
        WaWebChatAction a = new WaWebChatAction();
        a.setType(ActionType.ROUTED);
        return a;
    }

    private static WaWebMessage message() {
        WaWebMessage m = new WaWebMessage();
        m.setId("m1");
        m.setChatId("c1");
        m.setWaMessageId("wa1");
        m.setDirection(MessageDirection.IN);
        m.setSource(MessageSource.CONTACT);
        m.setType("image");
        m.setStatus(MessageStatus.SENT);
        m.setTimestamp(Instant.parse("2026-01-01T10:00:00Z"));
        return m;
    }
}
