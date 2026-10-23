package me.nageshk.omnichannel.inbox;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import me.nageshk.omnichannel.access.BuiltInRoles;
import me.nageshk.omnichannel.contact.Contact;
import me.nageshk.omnichannel.contact.ContactRepository;
import me.nageshk.omnichannel.contact.ContactSource;
import me.nageshk.omnichannel.phone.PhoneNumber;
import me.nageshk.omnichannel.support.TestDevices;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Fixture of the inbox API tests: one customer with a brand, a number, a team "Support" and four users
 * (manager and manager2 see all; agent is in team Support, which owns {@code number}; agent2 is in no team), plus
 * helpers to insert chats directly.
 */
@SpringBootTest
@AutoConfigureMockMvc
abstract class InboxApiTestBase {

    static final Instant T0 = Instant.parse("2026-10-01T10:00:00Z");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired TestUsers testUsers;
    @Autowired TestDevices testDevices;
    @Autowired TeamRepository teams;
    @Autowired me.nageshk.omnichannel.phone.PhoneNumberRepository phoneNumbers;
    @Autowired UserRepository users;
    @Autowired ContactRepository contacts;
    @Autowired WaWebChatRepository chats;
    @Autowired WaWebMessageRepository messages;
    @Autowired WaWebChatActionRepository actions;
    @Autowired MongoTemplate mongo;

    Customer acme;
    Workspace brand;
    Workspace other;
    PhoneNumber number;
    PhoneNumber otherNumber;
    Team support;
    User manager;
    User manager2;
    User agent;
    User agent2;
    String managerToken;
    String manager2Token;
    String agentToken;
    String agent2Token;

    @BeforeEach
    void setUpInbox() {
        clean();
        acme = testUsers.customer("Acme");
        brand = testUsers.workspace(acme.getId(), "Brand A");
        other = testUsers.workspace(acme.getId(), "Brand B");
        number = testDevices.number(brand, "9199" + (System.nanoTime() % 100000000));
        otherNumber = testDevices.number(other, "9188" + (System.nanoTime() % 100000000));
        manager = brandUser("manager", BuiltInRoles.WORKSPACE_MANAGER);
        manager2 = brandUser("manager2", BuiltInRoles.WORKSPACE_MANAGER);
        agent = brandUser("agent", BuiltInRoles.AGENT);
        agent2 = brandUser("agent2", BuiltInRoles.AGENT);
        support = team("Support", manager2, agent);
        number.setTeamId(support.getId());
        number = phoneNumbers.save(number);
        managerToken = testUsers.accessToken(manager);
        manager2Token = testUsers.accessToken(manager2);
        agentToken = testUsers.accessToken(agent);
        agent2Token = testUsers.accessToken(agent2);
    }

    @AfterEach
    void cleanUpInbox() {
        clean();
    }

    private void clean() {
        testUsers.deleteAll();
        for (Class<?> c : List.of(WaWebChat.class, WaWebMessage.class, WaWebChatAction.class, InboxSetting.class))
            mongo.remove(new Query(), c);
    }

    User brandUser(String username, String roleName) {
        return testUsers.createInCustomer(acme.getId(), username, null,
                List.of(new WorkspaceRole(brand.getId(), testUsers.roleId(roleName))));
    }

    Team team(String name, User... members) {
        Team t = new Team();
        t.setCustomerId(acme.getId());
        t.setWorkspaceId(brand.getId());
        t.setName(name);
        t.setMemberIds(new ArrayList<>(java.util.Arrays.stream(members).map(User::getId).toList()));
        return teams.save(t);
    }

    Contact contact(Workspace w, String name, String phone) {
        Contact c = new Contact();
        c.setWorkspaceId(w.getId());
        c.setCustomerId(acme.getId());
        c.setName(name);
        c.setPhone(phone);
        c.setSource(ContactSource.CHAT);
        return contacts.save(c);
    }

    /** An OPEN chat of the number at {@code T0 + minutes}, with an unread count of 2, unassigned. */
    WaWebChat chat(String name, String phone, long minutes) {
        return chat(number, brand, contact(brand, name, phone), minutes);
    }

    WaWebChat chat(PhoneNumber n, Workspace w, Contact contact, long minutes) {
        WaWebChat c = new WaWebChat();
        c.setCustomerId(acme.getId());
        c.setWorkspaceId(w.getId());
        c.setPhoneNumberId(n.getId());
        c.setRemoteId(UUID.randomUUID() + "@c.us");
        c.setContactId(contact.getId());
        c.setStatus(ChatStatus.OPEN);
        c.setUnreadCount(2);
        c.setLastMessageAt(T0.plusSeconds(minutes * 60));
        c.setLastMessage(new WaWebChat.LastMessage(MessageDirection.IN, "chat", "hello", null));
        return chats.save(c);
    }

    WaWebChat chat(String name, String phone, long minutes, User assignedUser, Team assignedTeam) {
        WaWebChat c = chat(name, phone, minutes);
        c.setAssignedUserId(assignedUser == null ? null : assignedUser.getId());
        c.setAssignedTeamId(assignedTeam == null ? null : assignedTeam.getId());
        return chats.save(c);
    }

    WaWebMessage message(WaWebChat chat, long seconds, String body) {
        WaWebMessage m = new WaWebMessage();
        m.setCustomerId(chat.getCustomerId());
        m.setWorkspaceId(chat.getWorkspaceId());
        m.setPhoneNumberId(chat.getPhoneNumberId());
        m.setChatId(chat.getId());
        m.setWaMessageId(UUID.randomUUID().toString());
        m.setDirection(MessageDirection.IN);
        m.setSource(MessageSource.CONTACT);
        m.setType("chat");
        m.setBody(body);
        m.setTimestamp(T0.plusSeconds(seconds));
        return messages.save(m);
    }

    WaWebChatAction action(WaWebChat chat, long seconds, ActionType type) {
        WaWebChatAction a = new WaWebChatAction();
        a.setCustomerId(chat.getCustomerId());
        a.setWorkspaceId(chat.getWorkspaceId());
        a.setChatId(chat.getId());
        a.setType(type);
        a.setByName("System");
        a.setAt(T0.plusSeconds(seconds));
        return actions.save(a);
    }

    String inbox() {
        return "/api/workspaces/" + brand.getId() + "/inbox";
    }

    String chatsUrl() {
        return inbox() + "/phone-numbers/" + number.getId() + "/chats";
    }

    ResultActions send(MockHttpServletRequestBuilder req, String token, Object body) throws Exception {
        if (token != null) req = req.header("Authorization", "Bearer " + token);
        if (body != null) req = req.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body));
        return mvc.perform(req);
    }

    JsonNode json(ResultActions result) throws Exception {
        return mapper.readTree(result.andReturn().getResponse().getContentAsString());
    }

    static List<String> names(JsonNode page) {
        List<String> out = new ArrayList<>();
        page.get("items").forEach(i -> out.add(i.get("contact").get("name").asString()));
        return out;
    }
}
