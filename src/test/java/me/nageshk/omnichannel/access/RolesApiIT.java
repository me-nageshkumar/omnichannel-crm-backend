package me.nageshk.omnichannel.access;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;
import me.nageshk.omnichannel.support.TestUsers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RolesApiIT {

    @Autowired TestRestTemplate rest;
    @Autowired TestUsers testUsers;
    @Autowired RoleRepository roles;
    HttpHeaders admin;

    @BeforeEach
    void setUp() {
        testUsers.deleteAll();
        admin = bearer(testUsers.accessToken(testUsers.create("root", "pw", BuiltInRoles.SUPER_ADMIN, null)));
    }

    private HttpHeaders bearer(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return h;
    }

    private ResponseEntity<JsonNode> call(HttpMethod method, String path, Object body) {
        return rest.exchange(path, method, new HttpEntity<>(body, admin), JsonNode.class);
    }

    private String idOf(String name) {
        return testUsers.roleId(name);
    }

    private ResponseEntity<JsonNode> create(String name, String level, List<String> authorities) {
        return call(HttpMethod.POST, "/api/roles",
                Map.of("name", name, "description", "d", "level", level, "authorities", authorities));
    }

    private JsonNode roleNamed(String name) {
        JsonNode all = call(HttpMethod.GET, "/api/roles", null).getBody();
        return StreamSupport.stream(all.spliterator(), false)
                .filter(r -> r.get("name").asText().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void listsAuthoritiesWithLevels() {
        ResponseEntity<JsonNode> res = call(HttpMethod.GET, "/api/authorities", null);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode userManage = StreamSupport.stream(res.getBody().spliterator(), false)
                .filter(a -> a.get("code").asText().equals("user:manage")).findFirst().orElseThrow();
        assertThat(userManage.get("level").asText()).isEqualTo("BRAND");
        assertThat(userManage.get("description").asText()).isNotBlank();
        assertThat(res.getBody().size()).isEqualTo(Authority.values().length);
    }

    @Test
    void listsRolesWithUserCount() {
        var acme = testUsers.customer("Acme");
        testUsers.createInCustomer(acme.getId(), "boss", "CUSTOMER_ADMIN", List.of());
        ResponseEntity<JsonNode> res = call(HttpMethod.GET, "/api/roles", null);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode admin = roleNamed("CUSTOMER_ADMIN");
        assertThat(admin.get("userCount").asInt()).isEqualTo(1);
        assertThat(admin.get("builtIn").asBoolean()).isTrue();
        assertThat(roleNamed("SUPER_ADMIN").get("userCount").asInt()).isEqualTo(1);
        assertThat(roleNamed("AGENT").get("userCount").asInt()).isZero();
        // ordered by level (PLATFORM, COMPANY, BRAND) then name
        List<String> levels = StreamSupport.stream(res.getBody().spliterator(), false)
                .map(r -> r.get("level").asText()).toList();
        assertThat(levels).isSortedAccordingTo(
                (a, b) -> RoleLevel.valueOf(a).compareTo(RoleLevel.valueOf(b)));
    }

    @Test
    void createsCustomBrandRole() {
        ResponseEntity<JsonNode> res = create("SUPPORT_LEAD", "BRAND", List.of("user:view", "team:manage"));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(res.getBody().get("builtIn").asBoolean()).isFalse();
        assertThat(res.getBody().get("level").asText()).isEqualTo("BRAND");
        assertThat(roles.findByName("SUPPORT_LEAD")).isPresent();
        assertThat(roles.findByName("SUPPORT_LEAD").get().getAuthorities())
                .containsExactlyInAnyOrder("user:view", "team:manage");
    }

    @Test
    void rejectsAuthorityAboveLevel() {
        ResponseEntity<JsonNode> res = create("TOO_HIGH", "BRAND", List.of("customer:create"));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().get("code").asText()).isEqualTo("VALIDATION");
        assertThat(res.getBody().get("fields").has("authorities")).isTrue();
        assertThat(roles.findByName("TOO_HIGH")).isEmpty();
    }

    @Test
    void rejectsUnknownAuthority() {
        ResponseEntity<JsonNode> res = create("MYSTERY", "BRAND", List.of("nope:nothing"));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().get("code").asText()).isEqualTo("VALIDATION");
    }

    @Test
    void rejectsBadNameWithFieldsMap() {
        ResponseEntity<JsonNode> res = create("lower", "BRAND", List.of());
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().get("fields").has("name")).isTrue();
        assertThat(res.getBody().get("message").asText()).isNotBlank();
    }

    @Test
    void duplicateNameIs409() {
        assertThat(create("TWICE", "BRAND", List.of()).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<JsonNode> dup = create("TWICE", "BRAND", List.of());
        assertThat(dup.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(dup.getBody().get("code").asText()).isEqualTo("DUPLICATE");
        assertThat(dup.getBody().get("field").asText()).isEqualTo("name");
        ResponseEntity<JsonNode> builtIn = create("AGENT", "BRAND", List.of());
        assertThat(builtIn.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void builtInNamesAreRefusedEvenWhenAbsent() {
        roles.deleteById(idOf("SUPER_ADMIN"));
        ResponseEntity<JsonNode> res = create("SUPER_ADMIN", "PLATFORM", List.of());
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(res.getBody().get("code").asText()).isEqualTo("DUPLICATE");
    }

    @Test
    void customRoleCannotBeRenamedToBuiltInName() {
        String id = create("RENAME_ME", "BRAND", List.of()).getBody().get("id").asText();
        ResponseEntity<JsonNode> res = call(HttpMethod.PUT, "/api/roles/" + id,
                Map.of("name", "AGENT", "description", "d", "authorities", List.of()));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        ResponseEntity<JsonNode> ok = call(HttpMethod.PUT, "/api/roles/" + id,
                Map.of("name", "RENAMED", "description", "d", "authorities", List.of()));
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ok.getBody().get("name").asText()).isEqualTo("RENAMED");
    }

    @Test
    void editsBuiltInAuthorities() {
        String id = idOf("TEAM_LEAD");
        ResponseEntity<JsonNode> res = call(HttpMethod.PUT, "/api/roles/" + id,
                Map.of("description", "lead", "authorities", List.of("user:view", "user:manage")));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(roles.findByName("TEAM_LEAD").get().getAuthorities())
                .containsExactlyInAnyOrder("user:view", "user:manage");
        assertThat(roles.findByName("TEAM_LEAD").get().getDescription()).isEqualTo("lead");
    }

    @Test
    void cannotRenameBuiltIn() {
        ResponseEntity<JsonNode> res = call(HttpMethod.PUT, "/api/roles/" + idOf("TEAM_LEAD"),
                Map.of("name", "CAPTAIN", "description", "d", "authorities", List.of()));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().get("code").asText()).isEqualTo("VALIDATION");
        assertThat(roles.findByName("TEAM_LEAD")).isPresent();
    }

    @Test
    void superAdminRoleIsNotEditable() {
        ResponseEntity<JsonNode> res = call(HttpMethod.PUT, "/api/roles/" + idOf("SUPER_ADMIN"),
                Map.of("description", "d", "authorities", List.of()));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void cannotDeleteBuiltIn() {
        ResponseEntity<JsonNode> res = call(HttpMethod.DELETE, "/api/roles/" + idOf("AGENT"), null);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(roles.findByName("AGENT")).isPresent();
    }

    @Test
    void cannotDeleteRoleInUse() {
        String id = create("BUSY", "PLATFORM", List.of()).getBody().get("id").asText();
        testUsers.create("busy-user", "pw", "BUSY", null);
        ResponseEntity<JsonNode> res = call(HttpMethod.DELETE, "/api/roles/" + id, null);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(res.getBody().get("code").asText()).isEqualTo("ROLE_IN_USE");
        assertThat(roles.findByName("BUSY")).isPresent();
    }

    @Test
    void deletesUnusedCustomRole() {
        String id = create("TEMP", "BRAND", List.of()).getBody().get("id").asText();
        assertThat(call(HttpMethod.DELETE, "/api/roles/" + id, null).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(roles.findByName("TEMP")).isEmpty();
        assertThat(call(HttpMethod.DELETE, "/api/roles/" + id, null).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void everyChangeIsAudited() {
        String id = create("AUDITED", "BRAND", List.of("user:view")).getBody().get("id").asText();
        call(HttpMethod.PUT, "/api/roles/" + id,
                Map.of("description", "new text", "authorities", List.of("team:manage")));
        call(HttpMethod.DELETE, "/api/roles/" + id, null);

        ResponseEntity<JsonNode> res = call(HttpMethod.GET, "/api/roles/" + id + "/changes", null);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode changes = res.getBody();
        assertThat(changes.size()).isEqualTo(3);
        assertThat(changes.get(0).get("action").asText()).isEqualTo("DELETED");
        assertThat(changes.get(0).get("removed").get(0).asText()).isEqualTo("team:manage");
        assertThat(changes.get(1).get("action").asText()).isEqualTo("UPDATED");
        assertThat(changes.get(1).get("added").get(0).asText()).isEqualTo("team:manage");
        assertThat(changes.get(1).get("removed").get(0).asText()).isEqualTo("user:view");
        assertThat(changes.get(1).get("descriptionBefore").asText()).isEqualTo("d");
        assertThat(changes.get(1).get("descriptionAfter").asText()).isEqualTo("new text");
        assertThat(changes.get(2).get("action").asText()).isEqualTo("CREATED");
        assertThat(changes.get(2).get("added").get(0).asText()).isEqualTo("user:view");
        assertThat(changes.get(2).get("changedBy").asText()).isEqualTo("root");
        assertThat(changes.get(2).get("roleName").asText()).isEqualTo("AUDITED");
    }

    @Test
    void previewReportsTeamLeadLosingAgent() {
        call(HttpMethod.PUT, "/api/roles/" + idOf("TEAM_LEAD"),
                Map.of("description", "lead", "authorities",
                        List.of("user:view", "user:manage", "contact:view", "contact:annotate")));
        ResponseEntity<JsonNode> res = call(HttpMethod.POST, "/api/roles/" + idOf("AGENT") + "/preview",
                Map.of("authorities", List.of("contact:view", "contact:annotate", "team:manage")));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<JsonNode> changes = StreamSupport.stream(res.getBody().get("changes").spliterator(), false).toList();
        assertThat(changes).anySatisfy(c -> {
            assertThat(c.get("assigner").asText()).isEqualTo("TEAM_LEAD");
            assertThat(c.get("assigned").asText()).isEqualTo("AGENT");
            assertThat(c.get("nowAssignable").asBoolean()).isFalse();
        });
        assertThat(changes).noneSatisfy(c -> assertThat(c.get("assigner").asText()).isEqualTo("AGENT"));
    }

    @Test
    void previewReportsCustomerAdminLosingWorkspaceManager() {
        call(HttpMethod.PUT, "/api/roles/" + idOf("WORKSPACE_MANAGER"), Map.of("description", "wm",
                "authorities", List.of("workspace:settings", "user:view", "user:manage")));
        call(HttpMethod.PUT, "/api/roles/" + idOf("CUSTOMER_ADMIN"), Map.of("description", "ca",
                "authorities", List.of("workspace:create", "company-user:manage", "workspace:settings",
                        "user:view", "user:manage")));
        ResponseEntity<JsonNode> res = call(HttpMethod.POST, "/api/roles/" + idOf("WORKSPACE_MANAGER") + "/preview",
                Map.of("authorities", List.of("workspace:settings", "user:view", "user:manage", "team:manage")));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<JsonNode> changes = StreamSupport.stream(res.getBody().get("changes").spliterator(), false).toList();
        assertThat(changes).anySatisfy(c -> {
            assertThat(c.get("assigner").asText()).isEqualTo("CUSTOMER_ADMIN");
            assertThat(c.get("assigned").asText()).isEqualTo("WORKSPACE_MANAGER");
            assertThat(c.get("nowAssignable").asBoolean()).isFalse();
        });
    }

    @Test
    void roleEditAppliesOnTheHoldersNextRequest() {
        HttpHeaders manager = bearer(testUsers.accessToken(
                testUsers.create("am", "pw", BuiltInRoles.ACCOUNT_MANAGER, null)));
        ResponseEntity<JsonNode> before = rest.exchange("/api/customers", HttpMethod.GET,
                new HttpEntity<>(manager), JsonNode.class);
        assertThat(before.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<JsonNode> edit = call(HttpMethod.PUT, "/api/roles/" + idOf("ACCOUNT_MANAGER"),
                Map.of("description", "am", "authorities", List.of("customer:create", "customer:manage")));
        assertThat(edit.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<JsonNode> after = rest.exchange("/api/customers", HttpMethod.GET,
                new HttpEntity<>(manager), JsonNode.class);
        assertThat(after.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void previewDoesNotSave() {
        Role before = roles.findByName("AGENT").orElseThrow();
        call(HttpMethod.POST, "/api/roles/" + before.getId() + "/preview",
                Map.of("authorities", List.of("team:manage")));
        assertThat(roles.findByName("AGENT").orElseThrow().getAuthorities()).isEqualTo(before.getAuthorities());
        assertThat(call(HttpMethod.GET, "/api/roles/" + before.getId() + "/changes", null).getBody().size()).isZero();
    }

    @Test
    void nonSuperAdminIs403() {
        HttpHeaders manager = bearer(testUsers.accessToken(
                testUsers.create("am", "pw", BuiltInRoles.ACCOUNT_MANAGER, null)));
        for (String path : new String[] {"/api/roles", "/api/authorities"}) {
            ResponseEntity<JsonNode> res = rest.exchange(path, HttpMethod.GET, new HttpEntity<>(manager), JsonNode.class);
            assertThat(res.getStatusCode()).as(path).isEqualTo(HttpStatus.FORBIDDEN);
        }
        ResponseEntity<JsonNode> post = rest.exchange("/api/roles", HttpMethod.POST,
                new HttpEntity<>(Map.of("name", "NOPE", "level", "BRAND", "authorities", List.of()), manager),
                JsonNode.class);
        assertThat(post.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void roleSaveWithUncreatedCodeIsRejected() {
        ResponseEntity<JsonNode> res = create("GHOSTLY", "BRAND", List.of("ghost:code"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().get("code").asText()).isEqualTo("VALIDATION");
        assertThat(res.getBody().get("fields").get("authorities").asText()).contains("ghost:code");
    }
}
