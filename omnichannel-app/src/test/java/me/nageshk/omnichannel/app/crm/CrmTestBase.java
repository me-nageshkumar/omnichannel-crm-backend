package me.nageshk.omnichannel.app.crm;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import me.nageshk.omnichannel.app.support.TestDevices;
import me.nageshk.omnichannel.app.support.TestUsers;
import me.nageshk.omnichannel.db.contact.Contact;
import me.nageshk.omnichannel.db.contact.ContactRepository;
import me.nageshk.omnichannel.db.contact.ContactSource;
import me.nageshk.omnichannel.db.enablement.EnablementChange;
import me.nageshk.omnichannel.db.inbox.ChatStatus;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import me.nageshk.omnichannel.db.inbox.WaWebChatAction;
import me.nageshk.omnichannel.db.inbox.WaWebChatRepository;
import me.nageshk.omnichannel.db.inbox.WaWebMessage;
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
import me.nageshk.omnichannel.security.auth.AuthenticatedUser;
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
 * Fixture of the CRM tests: customer "Acme" with brands {@code brand} and {@code other}; in {@code brand} the number
 * {@code number} owned by team {@code support} (members manager2 and agent) and {@code number2} with no team; users
 * manager (WORKSPACE_MANAGER), agent and agent2 (AGENT, agent2 in no team) and the platform user staff
 * (ACCOUNT_MANAGER). Every {@code crm*} collection, the chats and the contacts are cleared before and after each test.
 */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class CrmTestBase {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired TestUsers testUsers;
    @Autowired TestDevices testDevices;
    @Autowired TeamRepository teams;
    @Autowired PhoneNumberRepository phoneNumbers;
    @Autowired ContactRepository contacts;
    @Autowired WaWebChatRepository chats;
    @Autowired AccessService access;
    @Autowired MongoTemplate mongo;

    Customer acme;
    Workspace brand;
    Workspace other;
    PhoneNumber number;
    PhoneNumber number2;
    PhoneNumber otherNumber;
    Team support;
    User manager;
    User manager2;
    User agent;
    User agent2;
    User staff;
    String managerToken;
    String manager2Token;
    String agentToken;
    String agent2Token;
    String staffToken;

    @BeforeEach
    void setUpCrm() {
        clean();
        acme = testUsers.customer("Acme");
        brand = testUsers.workspace(acme.getId(), "Brand A");
        other = testUsers.workspace(acme.getId(), "Brand B");
        String suffix = String.valueOf(System.nanoTime() % 100000000);
        number = testDevices.number(brand, "9199" + suffix);
        number2 = testDevices.number(brand, "9198" + suffix);
        otherNumber = testDevices.number(other, "9188" + suffix);
        manager = brandUser("manager", BuiltInRoles.WORKSPACE_MANAGER);
        manager2 = brandUser("manager2", BuiltInRoles.WORKSPACE_MANAGER);
        agent = brandUser("agent", BuiltInRoles.AGENT);
        agent2 = brandUser("agent2", BuiltInRoles.AGENT);
        staff = testUsers.create("staff", "pw", BuiltInRoles.ACCOUNT_MANAGER, null);
        support = team("support", manager2, agent);
        number.setTeamId(support.getId());
        number = phoneNumbers.save(number);
        managerToken = testUsers.accessToken(manager);
        manager2Token = testUsers.accessToken(manager2);
        agentToken = testUsers.accessToken(agent);
        agent2Token = testUsers.accessToken(agent2);
        staffToken = testUsers.accessToken(staff);
    }

    @AfterEach
    void cleanUpCrm() {
        clean();
    }

    private void clean() {
        testUsers.deleteAll();
        for (Class<?> c : List.of(WaWebChat.class, WaWebMessage.class, WaWebChatAction.class))
            mongo.remove(new Query(), c);
        mongo.remove(new Query(), EnablementChange.class);
        for (String name : mongo.getCollectionNames())
            if (name.startsWith("crm")) mongo.remove(new Query(), name);
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
        t.setMemberIds(new ArrayList<>(Arrays.stream(members).map(User::getId).toList()));
        return teams.save(t);
    }

    AuthenticatedUser auth(User u) {
        return access.load(u);
    }

    /** A manually added contact of {@code brand}. */
    Contact contact(String name, String phone) {
        return contact(brand, name, phone);
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

    /** An OPEN chat of the number with the contact, optionally assigned. */
    WaWebChat chat(Contact contact, PhoneNumber n, User assignedUser, Team assignedTeam) {
        WaWebChat c = new WaWebChat();
        c.setCustomerId(acme.getId());
        c.setWorkspaceId(contact.getWorkspaceId());
        c.setPhoneNumberId(n.getId());
        c.setRemoteId(UUID.randomUUID() + "@c.us");
        c.setContactId(contact.getId());
        c.setStatus(ChatStatus.OPEN);
        c.setAssignedUserId(assignedUser == null ? null : assignedUser.getId());
        c.setAssignedTeamId(assignedTeam == null ? null : assignedTeam.getId());
        return chats.save(c);
    }

    String crm() {
        return "/api/workspaces/" + brand.getId() + "/crm";
    }

    ResultActions send(MockHttpServletRequestBuilder req, String token, Object body) throws Exception {
        if (token != null) req = req.header("Authorization", "Bearer " + token);
        if (body != null) req = req.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body));
        return mvc.perform(req);
    }

    JsonNode json(ResultActions result) throws Exception {
        return mapper.readTree(result.andReturn().getResponse().getContentAsString());
    }
}
