package me.nageshk.omnichannel.app.access;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;
import me.nageshk.omnichannel.app.support.TestUsers;
import me.nageshk.omnichannel.db.access.AuthorityChangeRepository;
import me.nageshk.omnichannel.security.access.BuiltInRoles;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/** End to end: an authority created in the console gates a real endpoint and reaches the Super Admin. */
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthorityLifecycleIT {

    @Autowired TestRestTemplate rest;
    @Autowired TestUsers testUsers;
    @Autowired AuthorityChangeRepository authorityChanges;
    HttpHeaders root;

    @BeforeEach
    void setUp() {
        testUsers.deleteAll();
        authorityChanges.deleteAll();
        root = bearer(testUsers.accessToken(testUsers.create("root", "pw", BuiltInRoles.SUPER_ADMIN, null)));
    }

    @AfterEach
    void tearDown() {
        testUsers.deleteAll();
        authorityChanges.deleteAll();
    }

    private static HttpHeaders bearer(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return h;
    }

    private ResponseEntity<JsonNode> call(HttpHeaders as, HttpMethod method, String path, Object body) {
        return rest.exchange(path, method, new HttpEntity<>(body, as), JsonNode.class);
    }

    private ResponseEntity<String> probe(HttpHeaders as) {
        return rest.exchange("/api/test-probe", HttpMethod.GET, new HttpEntity<>(as), String.class);
    }

    private List<String> meAuthorities() {
        JsonNode me = call(root, HttpMethod.GET, "/api/users/me", null).getBody();
        return StreamSupport.stream(me.get("authorities").spliterator(), false).map(JsonNode::asText).toList();
    }

    @Test
    void probeIsClosedUntilCreatedAndGranted() {
        ResponseEntity<JsonNode> role = call(root, HttpMethod.POST, "/api/roles",
                Map.of("name", "PROBER", "description", "d", "level", "PLATFORM", "authorities", List.of()));
        assertThat(role.getStatusCode()).as(String.valueOf(role.getBody())).isEqualTo(HttpStatus.CREATED);
        HttpHeaders pu = bearer(testUsers.accessToken(testUsers.create("pu", "pw", "PROBER", null)));

        assertThat(probe(pu).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<JsonNode> created = call(root, HttpMethod.POST, "/api/authorities",
                Map.of("code", "test:probe", "group", "test", "level", "PLATFORM", "description", "Probe"));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(probe(pu).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<JsonNode> edited = call(root, HttpMethod.PUT, "/api/roles/" + role.getBody().get("id").asString(),
                Map.of("name", "PROBER", "description", "d", "authorities", List.of("test:probe")));
        assertThat(edited.getStatusCode()).as(String.valueOf(edited.getBody())).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> res = probe(pu);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).isEqualTo("ok");
    }

    @Test
    void superAdminGainsAndLosesPlatformAuthority() {
        ResponseEntity<JsonNode> created = call(root, HttpMethod.POST, "/api/authorities",
                Map.of("code", "report:export", "group", "report", "level", "PLATFORM", "description", "Export"));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(meAuthorities()).contains("report:export");

        ResponseEntity<JsonNode> deleted = call(root, HttpMethod.DELETE, "/api/authorities/report:export", null);
        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(meAuthorities()).doesNotContain("report:export");
    }
}
