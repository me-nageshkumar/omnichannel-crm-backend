package me.nageshk.omnichannel.app.page;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import me.nageshk.omnichannel.app.support.TestUsers;
import me.nageshk.omnichannel.db.page.PagesAddressRepository;
import me.nageshk.omnichannel.db.page.PagesSettingsRepository;
import me.nageshk.omnichannel.db.tenant.Customer;
import me.nageshk.omnichannel.db.tenant.Workspace;
import me.nageshk.omnichannel.db.user.User;
import me.nageshk.omnichannel.security.access.BuiltInRoles;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/** Link addresses spec §5.8: the super admin's API for the platform's and brands' link addresses. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class PagesAddressApiIT {

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired TestUsers testUsers;
    @Autowired PagesAddressRepository repo;
    @Autowired PagesSettingsRepository settings;

    User root;
    User staff;
    Customer acme;
    Customer other;
    Workspace a;
    Workspace b;
    Workspace foreign;

    @BeforeEach
    void setUp() {
        clean();
        root = testUsers.create("root", "pw", BuiltInRoles.SUPER_ADMIN, null);
        staff = testUsers.create("staff", "pw", BuiltInRoles.ACCOUNT_MANAGER, null);
        acme = testUsers.customer("Acme");
        other = testUsers.customer("Other");
        a = testUsers.workspace(acme.getId(), "Brand A");
        b = testUsers.workspace(acme.getId(), "Brand B");
        foreign = testUsers.workspace(other.getId(), "Foreign");
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    private void clean() {
        repo.deleteAll();
        settings.deleteAll();
        testUsers.deleteAll();
    }

    private ResponseEntity<JsonNode> call(HttpMethod m, String path, User who, Object body) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(testUsers.accessToken(who));
        h.setContentType(MediaType.APPLICATION_JSON);
        // the console talks to the platform's host; "localhost" may be made a brand's domain below
        return rest.exchange("http://127.0.0.1:" + port + path, m, new HttpEntity<>(body, h), JsonNode.class);
    }

    private static Map<String, Object> body(String baseUrl, JsonNode version) {
        Map<String, Object> m = new HashMap<>();
        m.put("baseUrl", baseUrl);
        m.put("version", version == null || version.isNull() ? null : version.asLong());
        return m;
    }

    private String brandPath(Workspace w) {
        return "/api/customers/" + w.getCustomerId() + "/workspaces/" + w.getId() + "/pages-address";
    }

    private String self() {
        return "http://localhost:" + port;
    }

    @Test
    void everyEndpointNeedsThePermission() {
        List<ResponseEntity<JsonNode>> refused = List.of(
                call(HttpMethod.GET, "/api/platform/pages-address", staff, null),
                call(HttpMethod.PUT, "/api/platform/pages-address", staff, body("https://go.example.in", null)),
                call(HttpMethod.GET, "/api/customers/" + acme.getId() + "/pages-addresses", staff, null),
                call(HttpMethod.PUT, brandPath(a), staff, body("https://links.acme.in", null)),
                call(HttpMethod.POST, brandPath(a) + "/check", staff, null));
        refused.forEach(r -> assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThat(call(HttpMethod.GET, "/api/platform/pages-address", root, null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(call(HttpMethod.GET, "/api/customers/" + acme.getId() + "/pages-addresses", root, null).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void listsEveryBrandWithItsAddressAndThePlatformBase() {
        call(HttpMethod.PUT, brandPath(a), root, body(self(), null));
        JsonNode v = call(HttpMethod.GET, "/api/customers/" + acme.getId() + "/pages-addresses", root, null).getBody();
        assertThat(v.get("platformBaseUrl").asString()).isEqualTo("http://pages.test");
        assertThat(v.get("brands")).hasSize(2);
        assertThat(v.at("/brands/0/workspaceName").asString()).isEqualTo("Brand A");
        assertThat(v.at("/brands/0/address/baseUrl").asString()).isEqualTo(self());
        assertThat(v.at("/brands/1/address").isNull()).isTrue();
        assertThat(v.at("/brands/1/version").isNull()).isTrue();
    }

    @Test
    void settingReturnsTheCheckedRow() {
        ResponseEntity<JsonNode> r = call(HttpMethod.PUT, brandPath(a), root, body(self(), null));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody().at("/address/status").asString()).isEqualTo("WORKING");
        assertThat(r.getBody().at("/address/inUse").asBoolean()).isTrue();
        assertThat(r.getBody().at("/address/checkedAt").isNull()).isFalse();
        assertThat(r.getBody().get("workspaceId").asString()).isEqualTo(a.getId());
    }

    @Test
    void aBadAddressIsAFieldError() {
        ResponseEntity<JsonNode> r = call(HttpMethod.PUT, brandPath(a), root, body("links.acme.in", null));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(r.getBody().at("/fields/baseUrl").asString()).isEqualTo("Start the address with https://");
    }

    @Test
    void anotherCustomersDomainIs409() {
        call(HttpMethod.PUT, brandPath(foreign), root, body("https://links.shared.in", null));
        ResponseEntity<JsonNode> r = call(HttpMethod.PUT, brandPath(a), root, body("https://links.shared.in", null));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(r.getBody().get("code").asString()).isEqualTo("PAGES_HOST_TAKEN");
    }

    @Test
    void aStaleVersionIs409() {
        call(HttpMethod.PUT, brandPath(a), root, body(self(), null));
        Map<String, Object> stale = body("https://other.acme.in", null);
        stale.put("version", 99L);
        assertThat(call(HttpMethod.PUT, brandPath(a), root, stale).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void removingLeavesTheFormerHostWithItsEndDate() {
        JsonNode row = call(HttpMethod.PUT, brandPath(a), root, body("https://links.acme.in", null)).getBody();
        JsonNode after = call(HttpMethod.PUT, brandPath(a), root, body(null, row.get("version"))).getBody();
        assertThat(after.get("address").isNull()).isTrue();
        assertThat(after.at("/formerHosts/0/host").asString()).isEqualTo("links.acme.in");
        assertThat(after.at("/formerHosts/0/until").isNull()).isFalse();
    }

    @Test
    void checkOnABrandWithoutAnAddressIs404() {
        ResponseEntity<JsonNode> r = call(HttpMethod.POST, brandPath(b) + "/check", root, null);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(r.getBody().get("code").asString()).isEqualTo("NO_PAGES_ADDRESS");
    }

    @Test
    void checkReturnsTheRow() {
        call(HttpMethod.PUT, brandPath(a), root, body(self(), null));
        ResponseEntity<JsonNode> r = call(HttpMethod.POST, brandPath(a) + "/check", root, null);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody().at("/address/status").asString()).isEqualTo("WORKING");
    }

    @Test
    void platformGetSaysWhereItComesFrom() {
        JsonNode first = call(HttpMethod.GET, "/api/platform/pages-address", root, null).getBody();
        assertThat(first.get("source").asString()).isEqualTo("STARTUP");
        assertThat(first.get("baseUrl").asString()).isEqualTo("http://pages.test");
        JsonNode saved = call(HttpMethod.PUT, "/api/platform/pages-address", root, body(self(), first.get("version")))
                .getBody();
        assertThat(saved.get("source").asString()).isEqualTo("SAVED");
        assertThat(saved.get("startupBaseUrl").asString()).isEqualTo("http://pages.test");
        assertThat(saved.get("warning").isNull()).isTrue();
        JsonNode back = call(HttpMethod.PUT, "/api/platform/pages-address", root, body(null, saved.get("version")))
                .getBody();
        assertThat(back.get("source").asString()).isEqualTo("STARTUP");
    }

    @Test
    void platformPutWarnsButSaves() {
        ResponseEntity<JsonNode> r = call(HttpMethod.PUT, "/api/platform/pages-address", root,
                body(self() + "/nowhere", null));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody().get("warning").asString()).isEqualTo("The address answered, but not from this server");
        assertThat(r.getBody().get("baseUrl").asString()).isEqualTo(self() + "/nowhere");
    }
}
