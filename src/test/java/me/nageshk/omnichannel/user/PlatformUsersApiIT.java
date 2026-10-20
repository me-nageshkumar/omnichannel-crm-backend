package me.nageshk.omnichannel.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;
import me.nageshk.omnichannel.access.BuiltInRoles;
import me.nageshk.omnichannel.auth.LoginAttemptService;
import me.nageshk.omnichannel.support.TestUsers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest
@AutoConfigureMockMvc
class PlatformUsersApiIT {

    private static final String TEMP = "temporary-pass-1";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired TestUsers testUsers;
    @Autowired UserRepository users;
    @Autowired LoginAttemptService attempts;

    User root;
    String rootToken;

    @BeforeEach
    void setUp() {
        testUsers.deleteAll();
        List.of("root", "ann", "am1", "manager").forEach(attempts::clearLogin);
        root = testUsers.create("root", "pw", BuiltInRoles.SUPER_ADMIN, null);
        rootToken = testUsers.accessToken(root);
    }

    private ResultActions send(MockHttpServletRequestBuilder req, String token, Object body) throws Exception {
        if (token != null) req = req.header("Authorization", "Bearer " + token);
        if (body != null) req = req.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body));
        return mvc.perform(req);
    }

    private JsonNode json(ResultActions result) throws Exception {
        return mapper.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private Map<String, Object> newUser(String username, String roleName, String temporaryPassword) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", "Ann Manager");
        body.put("username", username);
        body.put("email", "ann@example.com");
        body.put("mobileNumber", "+911234567890");
        body.put("roleId", testUsers.roleId(roleName));
        body.put("temporaryPassword", temporaryPassword);
        return body;
    }

    private ResultActions login(String username, String password) throws Exception {
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("username", username, "password", password))));
    }

    @Test
    void superAdminCreatesAccountManager() throws Exception {
        JsonNode body = json(send(post("/api/platform-users"), rootToken, newUser("ann", BuiltInRoles.ACCOUNT_MANAGER, TEMP))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.temporaryPassword").value(TEMP))
                .andExpect(jsonPath("$.user.username").value("ann"))
                .andExpect(jsonPath("$.user.mustChangePassword").value(true))
                .andExpect(jsonPath("$.user.active").value(true))
                .andExpect(jsonPath("$.user.role.name").value(BuiltInRoles.ACCOUNT_MANAGER))
                .andExpect(jsonPath("$.user.password").doesNotExist())
                .andExpect(jsonPath("$.user.tokenVersion").doesNotExist()));

        User saved = users.findByUsername("ann").orElseThrow();
        assertThat(saved.getId()).isEqualTo(body.get("user").get("id").asText());
        assertThat(saved.getCustomerId()).isNull();
        assertThat(saved.isMustChangePassword()).isTrue();
        assertThat(saved.getPassword()).isNotEqualTo(TEMP);
        login("ann", TEMP).andExpect(status().isOk()).andExpect(jsonPath("$.passwordChangeRequired").value(true));

        JsonNode list = json(send(get("/api/platform-users"), rootToken, null).andExpect(status().isOk()));
        List<String> usernames = StreamSupport.stream(list.spliterator(), false).map(u -> u.get("username").asText()).toList();
        assertThat(usernames).contains("ann").doesNotContain("root");
    }

    @Test
    void accountManagerCannotListPlatformUsers() throws Exception {
        String am = testUsers.accessToken(testUsers.create("manager", "pw", BuiltInRoles.ACCOUNT_MANAGER, null));
        send(get("/api/platform-users"), am, null)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        send(post("/api/platform-users"), am, newUser("ann", BuiltInRoles.ACCOUNT_MANAGER, TEMP))
                .andExpect(status().isForbidden());
        assertThat(users.findByUsername("ann")).isEmpty();
    }

    @Test
    void cannotGiveSuperAdminRole() throws Exception {
        send(post("/api/platform-users"), rootToken, newUser("ann", BuiltInRoles.SUPER_ADMIN, TEMP))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        assertThat(users.findByUsername("ann")).isEmpty();

        User am = testUsers.create("manager", "pw", BuiltInRoles.ACCOUNT_MANAGER, null);
        Map<String, Object> edit = Map.of("name", "Promoted", "roleId", testUsers.roleId(BuiltInRoles.SUPER_ADMIN));
        send(put("/api/platform-users/" + am.getId()), rootToken, edit).andExpect(status().isForbidden());
        assertThat(users.findById(am.getId()).orElseThrow().getRoleId())
                .isEqualTo(testUsers.roleId(BuiltInRoles.ACCOUNT_MANAGER));
    }

    @Test
    void usernameStoredLowerCaseAndUnique() throws Exception {
        send(post("/api/platform-users"), rootToken, newUser("AM1", BuiltInRoles.ACCOUNT_MANAGER, TEMP))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.user.username").value("am1"));
        assertThat(users.findByUsername("am1")).isPresent();

        send(post("/api/platform-users"), rootToken, newUser("am1", BuiltInRoles.ACCOUNT_MANAGER, TEMP))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE"))
                .andExpect(jsonPath("$.field").value("username"));
    }

    @Test
    void resetPasswordForcesChangeAndRevokesTokens() throws Exception {
        User am = testUsers.create("manager", "pw", BuiltInRoles.ACCOUNT_MANAGER, null);
        String oldToken = testUsers.accessToken(am);
        send(get("/api/users/me"), oldToken, null).andExpect(status().isOk());

        send(post("/api/platform-users/" + am.getId() + "/reset-password"), rootToken, Map.of("temporaryPassword", TEMP))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.temporaryPassword").value(TEMP))
                .andExpect(jsonPath("$.user").doesNotExist());

        send(get("/api/users/me"), oldToken, null).andExpect(status().isUnauthorized());
        assertThat(users.findById(am.getId()).orElseThrow().isMustChangePassword()).isTrue();
        login("manager", TEMP).andExpect(status().isOk()).andExpect(jsonPath("$.passwordChangeRequired").value(true));
    }

    @Test
    void deactivateRevokesTokens() throws Exception {
        User am = testUsers.create("manager", "pw", BuiltInRoles.ACCOUNT_MANAGER, null);
        String oldToken = testUsers.accessToken(am);

        send(post("/api/platform-users/" + am.getId() + "/disable"), rootToken, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
        send(get("/api/users/me"), oldToken, null).andExpect(status().isUnauthorized());
        login("manager", "pw").andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("account_inactive"));

        send(post("/api/platform-users/" + am.getId() + "/enable"), rootToken, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true));
        // reactivation does not resurrect tokens issued before the deactivation
        send(get("/api/users/me"), oldToken, null).andExpect(status().isUnauthorized());
        login("manager", "pw").andExpect(status().isOk()).andExpect(jsonPath("$.token").isNotEmpty());
    }

    @Test
    void shortTemporaryPasswordIs400() throws Exception {
        send(post("/api/platform-users"), rootToken, newUser("ann", BuiltInRoles.ACCOUNT_MANAGER, "short"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("WEAK_PASSWORD"));
        assertThat(users.findByUsername("ann")).isEmpty();

        User am = testUsers.create("manager", "pw", BuiltInRoles.ACCOUNT_MANAGER, null);
        send(post("/api/platform-users/" + am.getId() + "/reset-password"), rootToken, Map.of("temporaryPassword", "short"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("WEAK_PASSWORD"));
        assertThat(users.findById(am.getId()).orElseThrow().isMustChangePassword()).isFalse();
    }

    @Test
    void editChangesDetailsAndRole() throws Exception {
        User am = testUsers.create("manager", "pw", BuiltInRoles.ACCOUNT_MANAGER, null);
        Map<String, Object> edit = Map.of("name", "Bridge", "email", "b@example.com", "mobileNumber", "+910000000000",
                "roleId", testUsers.roleId(BuiltInRoles.WHATSAPP_DEVICE));
        send(put("/api/platform-users/" + am.getId()), rootToken, edit)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Bridge"))
                .andExpect(jsonPath("$.email").value("b@example.com"))
                .andExpect(jsonPath("$.role.name").value(BuiltInRoles.WHATSAPP_DEVICE));
        User saved = users.findById(am.getId()).orElseThrow();
        assertThat(saved.getName()).isEqualTo("Bridge");
        assertThat(saved.getRoleId()).isEqualTo(testUsers.roleId(BuiltInRoles.WHATSAPP_DEVICE));
    }

    @Test
    void superAdminLoginIsNotManageable() throws Exception {
        send(post("/api/platform-users/" + root.getId() + "/disable"), rootToken, null)
                .andExpect(status().isForbidden());
        send(post("/api/platform-users/" + root.getId() + "/reset-password"), rootToken, Map.of("temporaryPassword", TEMP))
                .andExpect(status().isForbidden());
        assertThat(users.findById(root.getId()).orElseThrow().isActive()).isTrue();
    }

    @Test
    void customerUserOrUnknownIdIs404() throws Exception {
        var acme = testUsers.customer("Acme");
        User boss = testUsers.createInCustomer(acme.getId(), "boss", BuiltInRoles.CUSTOMER_ADMIN, List.of());
        send(post("/api/platform-users/" + boss.getId() + "/disable"), rootToken, null)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        send(post("/api/platform-users/nope/disable"), rootToken, null).andExpect(status().isNotFound());
    }

    @Test
    void assignableRolesArePlatformRolesExceptSuperAdmin() throws Exception {
        JsonNode roles = json(send(get("/api/platform-users/assignable-roles"), rootToken, null).andExpect(status().isOk()));
        List<String> names = StreamSupport.stream(roles.spliterator(), false).map(r -> r.get("name").asText()).toList();
        assertThat(names).containsExactlyInAnyOrder(BuiltInRoles.ACCOUNT_MANAGER, BuiltInRoles.WHATSAPP_DEVICE);
    }

    @Test
    void disableEnablePlatformUser() throws Exception {
        User am = testUsers.create("manager", "pw", BuiltInRoles.ACCOUNT_MANAGER, null);
        send(post("/api/platform-users/" + am.getId() + "/disable"), rootToken, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.disabled.level").value("PLATFORM"));
        send(post("/api/platform-users/" + am.getId() + "/enable"), rootToken, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.disabled").isEmpty());
    }
}
