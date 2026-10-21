package me.nageshk.omnichannel.campaign;

import java.time.Instant;
import java.util.List;
import me.nageshk.omnichannel.access.BuiltInRoles;
import me.nageshk.omnichannel.phone.PhoneNumber;
import me.nageshk.omnichannel.support.MutableClock;
import me.nageshk.omnichannel.support.TestDevices;
import me.nageshk.omnichannel.support.TestUsers;
import me.nageshk.omnichannel.tenant.Customer;
import me.nageshk.omnichannel.tenant.Workspace;
import me.nageshk.omnichannel.user.User;
import me.nageshk.omnichannel.user.WorkspaceRole;
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
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * Fixture of the campaign tests: a customer with two workspaces, three numbers and three brand users, a movable
 * clock, and a REST client against the running server.
 */
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "omnichannel.rpc.timeout=2s")
@Import(CampaignTestBase.Fixtures.class)
abstract class CampaignTestBase {

    /** Wednesday 2026-10-21, 11:00 in Asia/Kolkata. */
    static final Instant WED_11_IST = Instant.parse("2026-10-21T05:30:00Z");

    @TestConfiguration
    static class Fixtures {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(WED_11_IST);
        }
    }

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired MutableClock clock;
    @Autowired TestUsers testUsers;
    @Autowired TestDevices testDevices;
    @Autowired MongoTemplate mongo;

    Customer acme;
    Workspace brand;
    Workspace other;
    PhoneNumber n1;
    PhoneNumber n2;
    PhoneNumber otherNumber;
    User manager;
    User lead;
    User agent;

    /** The collections each test starts without; later tasks add their documents here. */
    List<Class<?>> collections() {
        return List.of(CampaignSettings.class);
    }

    @BeforeEach
    void setUpCampaigns() {
        clean();
        acme = testUsers.customer("Acme");
        brand = testUsers.workspace(acme.getId(), "Brand A");
        other = testUsers.workspace(acme.getId(), "Brand B");
        n1 = testDevices.number(brand, "9198" + (System.nanoTime() % 100000000));
        n2 = testDevices.number(brand, "9197" + (System.nanoTime() % 100000000));
        otherNumber = testDevices.number(other, "9196" + (System.nanoTime() % 100000000));
        manager = brandUser(brand, "manager", BuiltInRoles.WORKSPACE_MANAGER);
        lead = brandUser(brand, "lead", BuiltInRoles.TEAM_LEAD);
        agent = brandUser(brand, "agent", BuiltInRoles.AGENT);
    }

    @AfterEach
    void cleanUpCampaigns() {
        clean();
    }

    private void clean() {
        testUsers.deleteAll();
        for (Class<?> c : collections()) mongo.remove(new Query(), c);
        clock.set(WED_11_IST);
    }

    User brandUser(Workspace w, String username, String roleName) {
        return testUsers.createInCustomer(acme.getId(), username, null,
                List.of(new WorkspaceRole(w.getId(), testUsers.roleId(roleName))));
    }

    /** A fresh token: it carries the clock's current time. */
    String token(User u) {
        return testUsers.accessToken(u);
    }

    String url(String path) {
        return "http://localhost:" + port + path;
    }

    HttpEntity<Object> auth(User who, Object body) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token(who));
        h.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, h);
    }

    /** {@code path} is relative to the brand's {@code /api/workspaces/{id}}. */
    ResponseEntity<JsonNode> api(HttpMethod m, String path, User who, Object body) {
        return rest.exchange(url("/api/workspaces/" + brand.getId() + path), m, auth(who, body), JsonNode.class);
    }
}
