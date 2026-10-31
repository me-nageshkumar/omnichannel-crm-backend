package me.nageshk.omnichannel.app.inbox.history;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import me.nageshk.omnichannel.app.support.MutableClock;
import me.nageshk.omnichannel.app.support.TestDevices;
import me.nageshk.omnichannel.app.support.TestUsers;
import me.nageshk.omnichannel.db.contact.Contact;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import me.nageshk.omnichannel.db.inbox.WaWebChatAction;
import me.nageshk.omnichannel.db.inbox.HistoryScopeKind;
import me.nageshk.omnichannel.db.inbox.WaWebChatRepository;
import me.nageshk.omnichannel.db.inbox.WaWebHistoryImport;
import me.nageshk.omnichannel.db.inbox.WaWebHistoryImportChat;
import me.nageshk.omnichannel.db.inbox.WaWebHistoryImportChatRepository;
import me.nageshk.omnichannel.db.inbox.WaWebHistoryImportRepository;
import me.nageshk.omnichannel.db.inbox.WaWebMessage;
import me.nageshk.omnichannel.db.inbox.WaWebMessageRepository;
import me.nageshk.omnichannel.db.phone.PhoneNumber;
import me.nageshk.omnichannel.db.tenant.Customer;
import me.nageshk.omnichannel.db.tenant.Workspace;
import me.nageshk.omnichannel.db.user.User;
import me.nageshk.omnichannel.db.user.WorkspaceRole;
import me.nageshk.omnichannel.security.access.BuiltInRoles;
import me.nageshk.omnichannel.security.auth.AuthenticatedUser;
import me.nageshk.omnichannel.service.inbox.history.HistoryImportRequest;
import me.nageshk.omnichannel.service.inbox.history.HistoryImportRequest.ScopeRequest;
import me.nageshk.omnichannel.service.inbox.history.HistoryImportRunner;
import me.nageshk.omnichannel.service.inbox.history.HistoryImportService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * Fixture of the history import tests: a customer with two workspaces, two numbers and three brand users, a movable
 * clock, and a REST client against the running server.
 */
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "omnichannel.rpc.timeout=2s")
@Import(HistoryImportTestBase.Fixtures.class)
abstract class HistoryImportTestBase {

    static final Instant START = Instant.parse("2026-10-21T05:30:00Z");

    @TestConfiguration
    static class Fixtures {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(START);
        }
    }

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired MutableClock clock;
    @Autowired TestUsers testUsers;
    @Autowired TestDevices testDevices;
    @Autowired MongoTemplate mongo;
    @Autowired HistoryImportService service;
    @Autowired HistoryImportRunner runner;
    @Autowired WaWebHistoryImportRepository imports;
    @Autowired WaWebHistoryImportChatRepository items;
    @Autowired WaWebMessageRepository messages;
    @Autowired WaWebChatRepository chats;

    Customer acme;
    Workspace brand;
    Workspace other;
    PhoneNumber n1;
    PhoneNumber otherNumber;
    User manager;
    User agent;
    String managerToken;
    String agentToken;

    @BeforeEach
    void setUpHistory() {
        clean();
        acme = testUsers.customer("Acme");
        brand = testUsers.workspace(acme.getId(), "Brand A");
        other = testUsers.workspace(acme.getId(), "Brand B");
        n1 = testDevices.number(brand, "9198" + (System.nanoTime() % 100000000));
        otherNumber = testDevices.number(other, "9196" + (System.nanoTime() % 100000000));
        manager = brandUser(brand, "manager", BuiltInRoles.WORKSPACE_MANAGER);
        agent = brandUser(brand, "agent", BuiltInRoles.AGENT);
        managerToken = token(manager);
        agentToken = token(agent);
    }

    @AfterEach
    void cleanUpHistory() {
        clean();
    }

    private void clean() {
        testUsers.deleteAll();
        for (Class<?> c : List.of(WaWebHistoryImport.class, WaWebHistoryImportChat.class, WaWebChat.class,
                WaWebMessage.class, WaWebChatAction.class, Contact.class)) {
            mongo.remove(new Query(), c);
        }
        clock.set(START);
    }

    User brandUser(Workspace w, String username, String roleName) {
        return testUsers.createInCustomer(acme.getId(), username, null,
                List.of(new WorkspaceRole(w.getId(), testUsers.roleId(roleName))));
    }

    /** A fresh token: it carries the clock's current time. */
    String token(User u) {
        return testUsers.accessToken(u);
    }

    /** An import of every chat of {@code n1}, requested by the manager. */
    WaWebHistoryImport importAll(Instant from, Instant to) {
        return service.create(new AuthenticatedUser(manager, Set.of()), brand.getId(), n1.getId(),
                new HistoryImportRequest(new ScopeRequest(HistoryScopeKind.ALL, null, null), from, to)).imp();
    }

    /** An import of the chats of these phones on {@code n1}, requested by the manager. */
    WaWebHistoryImport importChats(String... phones) {
        return service.create(new AuthenticatedUser(manager, Set.of()), brand.getId(), n1.getId(),
                new HistoryImportRequest(new ScopeRequest(HistoryScopeKind.CHATS, List.of(phones), null), null,
                        null)).imp();
    }

    WaWebHistoryImport reload(WaWebHistoryImport imp) {
        return imports.findById(imp.getId()).orElseThrow();
    }

    List<WaWebHistoryImportChat> itemsOf(WaWebHistoryImport imp) {
        return mongo.find(new Query(Criteria.where("importId").is(imp.getId())).with(Sort.by("_id")),
                WaWebHistoryImportChat.class);
    }

    String url(String path) {
        return "http://localhost:" + port + path;
    }

    /** {@code path} is relative to the brand's {@code /api/workspaces/{id}/inbox}. */
    ResponseEntity<JsonNode> api(HttpMethod m, String path, User who, Object body) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token(who));
        h.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(url("/api/workspaces/" + brand.getId() + "/inbox" + path), m,
                new HttpEntity<>(body, h), JsonNode.class);
    }
}
