package me.nageshk.omnichannel.app.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.StreamSupport;
import me.nageshk.omnichannel.app.api.ApiExceptionHandler;
import me.nageshk.omnichannel.app.support.TestUsers;
import me.nageshk.omnichannel.db.access.AuthorityChangeRepository;
import me.nageshk.omnichannel.db.access.AuthorityDefinition;
import me.nageshk.omnichannel.db.access.AuthorityDefinitionRepository;
import me.nageshk.omnichannel.db.access.RoleLevel;
import me.nageshk.omnichannel.security.access.AuthorityService;
import me.nageshk.omnichannel.security.access.BuiltInRoles;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthoritiesApiIT {

    @Autowired TestRestTemplate rest;
    @Autowired TestUsers testUsers;
    @Autowired AuthorityDefinitionRepository definitions;
    @Autowired AuthorityChangeRepository authorityChanges;
    @Autowired AuthorityService service;
    HttpHeaders root;

    /** Resets users, roles and authorities (the seeder recreates built-ins) and the authority history. */
    @BeforeEach
    void setUp() {
        testUsers.deleteAll();
        authorityChanges.deleteAll();
        root = bearer(testUsers.accessToken(testUsers.create("root", "pw", BuiltInRoles.SUPER_ADMIN, null)));
    }

    private static HttpHeaders bearer(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return h;
    }

    private ResponseEntity<JsonNode> call(HttpMethod method, String path, Object body) {
        return rest.exchange(path, method, new HttpEntity<>(body, root), JsonNode.class);
    }

    private ResponseEntity<JsonNode> create(String code, String group, String level, String description) {
        return call(HttpMethod.POST, "/api/authorities",
                Map.of("code", code, "group", group, "level", level, "description", description));
    }

    private ResponseEntity<JsonNode> put(String code, String group, String level, String description) {
        return call(HttpMethod.PUT, "/api/authorities/" + code,
                Map.of("group", group, "level", level, "description", description));
    }

    private List<JsonNode> list() {
        ResponseEntity<JsonNode> res = call(HttpMethod.GET, "/api/authorities", null);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        return StreamSupport.stream(res.getBody().spliterator(), false).toList();
    }

    private Optional<JsonNode> listed(String code) {
        return list().stream().filter(a -> a.get("code").asString().equals(code)).findFirst();
    }

    private List<JsonNode> needsCreating() {
        ResponseEntity<JsonNode> res = call(HttpMethod.GET, "/api/authorities/needs-creating", null);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        return StreamSupport.stream(res.getBody().spliterator(), false).toList();
    }

    private static List<String> texts(JsonNode array) {
        return StreamSupport.stream(array.spliterator(), false).map(JsonNode::asText).toList();
    }

    private void addToAgent(String code) {
        JsonNode agent = StreamSupport.stream(call(HttpMethod.GET, "/api/roles", null).getBody().spliterator(), false)
                .filter(r -> r.get("name").asString().equals("AGENT")).findFirst().orElseThrow();
        List<String> authorities = new ArrayList<>(texts(agent.get("authorities")));
        authorities.add(code);
        ResponseEntity<JsonNode> res = call(HttpMethod.PUT, "/api/roles/" + agent.get("id").asString(),
                Map.of("description", agent.get("description").asString(), "authorities", authorities));
        assertThat(res.getStatusCode()).as(String.valueOf(res.getBody())).isEqualTo(HttpStatus.OK);
    }

    @Test
    void listShowsBuiltInsGroupedAndActive() {
        List<JsonNode> all = list();
        assertThat(all.size()).isGreaterThanOrEqualTo(11);
        List<String> keys = all.stream().map(a -> a.get("group").asString() + " " + a.get("code").asString()).toList();
        assertThat(keys).isSorted();
        List<Integer> customerPositions = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).get("code").asString().startsWith("customer:")) customerPositions.add(i);
        }
        assertThat(customerPositions).hasSize(3);
        assertThat(customerPositions.get(2) - customerPositions.get(0)).isEqualTo(2);

        JsonNode view = listed("customer:view").orElseThrow();
        assertThat(view.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(view.get("builtIn").asBoolean()).isTrue();
        assertThat(texts(view.get("roles"))).contains("ACCOUNT_MANAGER");
        assertThat(view.get("level").asString()).isEqualTo("PLATFORM");
        assertThat(view.has("checkedIn")).isTrue();
    }

    @Test
    void needsCreatingListsProbe() {
        JsonNode probe = needsCreating().stream().filter(n -> n.get("code").asString().equals("test:probe"))
                .findFirst().orElseThrow();
        assertThat(probe.get("suggestedLevel").isNull()).isTrue();
        assertThat(probe.get("suggestedGroup").asString()).isEqualTo("test");
        assertThat(texts(probe.get("checkedIn"))).containsExactly("ProbeController.probe");
    }

    @Test
    void createThenListShowsActive() {
        ResponseEntity<JsonNode> res = create("test:probe", "test", "PLATFORM", "Probe");
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(res.getBody().get("code").asString()).isEqualTo("test:probe");
        assertThat(res.getBody().get("builtIn").asBoolean()).isFalse();
        assertThat(needsCreating()).noneMatch(n -> n.get("code").asString().equals("test:probe"));
        JsonNode view = listed("test:probe").orElseThrow();
        assertThat(view.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(texts(view.get("checkedIn"))).containsExactly("ProbeController.probe");
        assertThat(view.get("updatedBy").asString()).isEqualTo("root");
    }

    @Test
    void createUncheckedCodeIsNotChecked() {
        ResponseEntity<JsonNode> res = create("order:refnd", "order", "BRAND", "Typo");
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(res.getBody().get("status").asString()).isEqualTo("NOT_CHECKED");
        assertThat(listed("order:refnd").orElseThrow().get("status").asString()).isEqualTo("NOT_CHECKED");
    }

    @Test
    void createBrandCodeCheckedGloballyIsWrongLevel() {
        ResponseEntity<JsonNode> res = create("test:probe", "test", "BRAND", "Probe");
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(res.getBody().get("status").asString()).isEqualTo("WRONG_LEVEL");
    }

    @Test
    void createDuplicateIs409() {
        ResponseEntity<JsonNode> res = create("customer:view", "customer", "PLATFORM", "Again");
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(res.getBody().get("code").asString()).isEqualTo("DUPLICATE");
        assertThat(res.getBody().get("field").asString()).isEqualTo("code");
    }

    @Test
    void createValidation() {
        ResponseEntity<JsonNode> res = create("Customer:View", "Cust omer", "BRAND", "   ");
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().get("code").asString()).isEqualTo("VALIDATION");
        JsonNode fields = res.getBody().get("fields");
        assertThat(fields.has("code")).isTrue();
        assertThat(fields.has("group")).isTrue();
        assertThat(fields.has("description")).isTrue();
        assertThat(definitions.findById("Customer:View")).isEmpty();
    }

    @Test
    void editDescriptionAndGroupOfBuiltIn() {
        ResponseEntity<JsonNode> res = put("customer:view", "clients", "PLATFORM", "  See customers  ");
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody().get("group").asString()).isEqualTo("clients");
        JsonNode view = listed("customer:view").orElseThrow();
        assertThat(view.get("group").asString()).isEqualTo("clients");
        assertThat(view.get("description").asString()).isEqualTo("See customers");
        assertThat(view.get("updatedBy").asString()).isEqualTo("root");
    }

    @Test
    void builtInLevelChangeIsForbidden() {
        ResponseEntity<JsonNode> res = put("customer:view", "customer", "COMPANY", "See customers");
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(res.getBody().get("code").asString()).isEqualTo("FORBIDDEN");
        assertThat(definitions.findById("customer:view").orElseThrow().getLevel()).isEqualTo(RoleLevel.PLATFORM);
    }

    @Test
    void levelChangeInUseIs409() {
        assertThat(create("order:refund", "order", "BRAND", "Refund").getStatusCode()).isEqualTo(HttpStatus.CREATED);
        addToAgent("order:refund");

        ResponseEntity<JsonNode> res = put("order:refund", "order", "COMPANY", "Refund");
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(res.getBody().get("code").asString()).isEqualTo("AUTHORITY_IN_USE");
        assertThat(res.getBody().get("message").asString()).isNotBlank();
        assertThat(texts(res.getBody().get("roles"))).containsExactly("AGENT");
        assertThat(definitions.findById("order:refund").orElseThrow().getLevel()).isEqualTo(RoleLevel.BRAND);
    }

    @Test
    void deleteInUseIs409AndBuiltInIs403() {
        assertThat(create("order:refund", "order", "BRAND", "Refund").getStatusCode()).isEqualTo(HttpStatus.CREATED);
        addToAgent("order:refund");

        ResponseEntity<JsonNode> inUse = call(HttpMethod.DELETE, "/api/authorities/order:refund", null);
        assertThat(inUse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(inUse.getBody().get("code").asString()).isEqualTo("AUTHORITY_IN_USE");
        assertThat(texts(inUse.getBody().get("roles"))).containsExactly("AGENT");
        assertThat(definitions.existsById("order:refund")).isTrue();

        ResponseEntity<JsonNode> builtIn = call(HttpMethod.DELETE, "/api/authorities/customer:view", null);
        assertThat(builtIn.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(builtIn.getBody().get("code").asString()).isEqualTo("FORBIDDEN");
        assertThat(definitions.existsById("customer:view")).isTrue();
    }

    @Test
    void deleteUnusedRemovesIt() {
        assertThat(create("order:x", "order", "BRAND", "X").getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<JsonNode> res = call(HttpMethod.DELETE, "/api/authorities/order:x", null);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(listed("order:x")).isEmpty();

        ResponseEntity<JsonNode> history = call(HttpMethod.GET, "/api/authorities/order:x/history", null);
        assertThat(history.getStatusCode()).isEqualTo(HttpStatus.OK);
        // newest first: the delete, then the create
        assertThat(StreamSupport.stream(history.getBody().spliterator(), false).map(c -> c.get("action").asString()))
                .containsExactly("DELETED", "CREATED");
    }

    @Test
    void historyRecordsUpdate() {
        assertThat(put("customer:view", "clients", "PLATFORM", "See customers").getStatusCode())
                .isEqualTo(HttpStatus.OK);

        ResponseEntity<JsonNode> history = call(HttpMethod.GET, "/api/authorities/customer:view/history", null);
        assertThat(history.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode first = history.getBody().get(0);
        assertThat(first.get("action").asString()).isEqualTo("UPDATED");
        assertThat(first.get("code").asString()).isEqualTo("customer:view");
        assertThat(first.get("groupBefore").asString()).isEqualTo("customer");
        assertThat(first.get("groupAfter").asString()).isEqualTo("clients");
        assertThat(first.get("levelBefore").asString()).isEqualTo("PLATFORM");
        assertThat(first.get("levelAfter").asString()).isEqualTo("PLATFORM");
        assertThat(first.get("descriptionAfter").asString()).isEqualTo("See customers");
        assertThat(first.get("changedBy").asString()).isEqualTo("root");
        assertThat(first.get("at").isNull()).isFalse();
    }

    @Test
    void unknownCodeIs404() {
        assertThat(put("nope:x", "nope", "BRAND", "d").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(HttpMethod.DELETE, "/api/authorities/nope:x", null).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(HttpMethod.GET, "/api/authorities/nope:x/history", null).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void notSuperAdminIs403() {
        HttpHeaders am = bearer(testUsers.accessToken(testUsers.create("am", "pw", BuiltInRoles.ACCOUNT_MANAGER, null)));
        ResponseEntity<JsonNode> res = rest.exchange("/api/authorities", HttpMethod.GET, new HttpEntity<>(am),
                JsonNode.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void colonInPathReachesController() {
        ResponseEntity<JsonNode> raw = put("customer:view", "customer", "PLATFORM", "raw colon");
        assertThat(raw.getStatusCode()).as(String.valueOf(raw.getBody())).isEqualTo(HttpStatus.OK);

        URI encoded = URI.create(rest.getRootUri() + "/api/authorities/customer%3Aview");
        ResponseEntity<JsonNode> enc = rest.exchange(encoded, HttpMethod.PUT, new HttpEntity<>(
                Map.of("group", "customer", "level", "PLATFORM", "description", "encoded colon"), root), JsonNode.class);
        assertThat(enc.getStatusCode()).as(String.valueOf(enc.getBody())).isEqualTo(HttpStatus.OK);
        assertThat(enc.getBody().get("code").asString()).isEqualTo("customer:view");
        assertThat(definitions.findById("customer:view").orElseThrow().getDescription()).isEqualTo("encoded colon");
    }

    @Test
    void roleSaveRightAfterCreateAcceptsTheNewCode() {
        assertThat(create("order:new", "order", "BRAND", "New").getStatusCode()).isEqualTo(HttpStatus.CREATED);
        addToAgent("order:new");
    }

    @Test
    void staleVersionIs409Conflict() {
        AuthorityDefinition stale = definitions.findById("customer:view").orElseThrow();
        assertThat(put("customer:view", "customer", "PLATFORM", "fresh").getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThatThrownBy(() -> service.update(stale, "customer", RoleLevel.PLATFORM, "stale", "root"))
                .isInstanceOfSatisfying(OptimisticLockingFailureException.class, e -> {
                    ResponseEntity<Map<String, String>> reply = new ApiExceptionHandler().conflict(e);
                    assertThat(reply.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(reply.getBody().get("code")).isEqualTo("CONFLICT");
                });
        assertThat(definitions.findById("customer:view").orElseThrow().getDescription()).isEqualTo("fresh");
    }
}
