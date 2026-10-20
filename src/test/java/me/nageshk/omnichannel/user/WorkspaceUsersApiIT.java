package me.nageshk.omnichannel.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;
import me.nageshk.omnichannel.access.BuiltInRoles;
import me.nageshk.omnichannel.auth.LoginAttemptService;
import me.nageshk.omnichannel.support.TestUsers;
import me.nageshk.omnichannel.tenant.Customer;
import me.nageshk.omnichannel.tenant.Team;
import me.nageshk.omnichannel.tenant.TeamRepository;
import me.nageshk.omnichannel.tenant.Workspace;
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
class WorkspaceUsersApiIT {

    private static final String TEMP = "temporary-pass-1";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired TestUsers testUsers;
    @Autowired UserRepository users;
    @Autowired TeamRepository teams;
    @Autowired LoginAttemptService attempts;

    Customer acme;
    Workspace brandA;
    Workspace brandB;
    Customer globex;
    Workspace globexShop;
    User boss;
    User wm;
    User lead;
    User agent;
    User gboss;
    String bossToken;
    String wmToken;
    String leadToken;

    @BeforeEach
    void setUp() {
        testUsers.deleteAll();
        List.of("newagent", "newwm").forEach(attempts::clearLogin);
        acme = testUsers.customer("Acme");
        brandA = testUsers.workspace(acme.getId(), "Brand A");
        brandB = testUsers.workspace(acme.getId(), "Brand B");
        globex = testUsers.customer("Globex");
        globexShop = testUsers.workspace(globex.getId(), "Globex Shop");
        boss = testUsers.createInCustomer(acme.getId(), "boss", BuiltInRoles.CUSTOMER_ADMIN, List.of());
        wm = inA("wm", BuiltInRoles.WORKSPACE_MANAGER);
        lead = inA("lead", BuiltInRoles.TEAM_LEAD);
        agent = inA("agent", BuiltInRoles.AGENT);
        gboss = testUsers.createInCustomer(globex.getId(), "gboss", BuiltInRoles.CUSTOMER_ADMIN, List.of());
        bossToken = testUsers.accessToken(boss);
        wmToken = testUsers.accessToken(wm);
        leadToken = testUsers.accessToken(lead);
    }

    private User inA(String username, String roleName) {
        return testUsers.createInCustomer(acme.getId(), username, null, List.of(role(brandA, roleName)));
    }

    private WorkspaceRole role(Workspace w, String roleName) {
        return new WorkspaceRole(w.getId(), testUsers.roleId(roleName));
    }

    private String usersOf(Workspace w) {
        return "/api/workspaces/" + w.getId() + "/users";
    }

    private ResultActions send(MockHttpServletRequestBuilder req, String token, Object body) throws Exception {
        if (token != null) req = req.header("Authorization", "Bearer " + token);
        if (body != null) req = req.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body));
        return mvc.perform(req);
    }

    private JsonNode json(ResultActions result) throws Exception {
        return mapper.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private Map<String, Object> newUser(String username, String roleName) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", "New Person");
        body.put("username", username);
        body.put("email", "new@example.com");
        body.put("mobileNumber", "+911234567890");
        body.put("temporaryPassword", TEMP);
        body.put("roleId", testUsers.roleId(roleName));
        return body;
    }

    private Map<String, Object> existing(User u, String roleName) {
        return Map.of("userId", u.getId(), "roleId", testUsers.roleId(roleName));
    }

    private Team team(Workspace w, String name, String... memberIds) {
        Team t = new Team();
        t.setCustomerId(w.getCustomerId());
        t.setWorkspaceId(w.getId());
        t.setName(name);
        t.setMemberIds(new ArrayList<>(List.of(memberIds)));
        return teams.save(t);
    }

    private static JsonNode byUsername(JsonNode list, String username) {
        return StreamSupport.stream(list.spliterator(), false)
                .filter(u -> username.equals(u.get("username").asText()))
                .findFirst().orElse(null);
    }

    @Test
    void listIncludesCustomerAdminAsAllBrands() throws Exception {
        testUsers.createInCustomer(acme.getId(), "bonly", null, List.of(role(brandB, BuiltInRoles.AGENT)));

        JsonNode list = json(send(get(usersOf(brandA)), wmToken, null).andExpect(status().isOk()));
        assertThat(StreamSupport.stream(list.spliterator(), false).map(u -> u.get("username").asText()).toList())
                .containsExactlyInAnyOrder("boss", "wm", "lead", "agent");
        JsonNode bossRow = byUsername(list, "boss");
        assertThat(bossRow.get("allBrands").asBoolean()).isTrue();
        assertThat(bossRow.get("manageable").asBoolean()).isFalse();
        assertThat(bossRow.get("role").get("name").asText()).isEqualTo(BuiltInRoles.CUSTOMER_ADMIN);
        JsonNode agentRow = byUsername(list, "agent");
        assertThat(agentRow.get("allBrands").asBoolean()).isFalse();
        assertThat(agentRow.get("manageable").asBoolean()).isTrue();
        assertThat(agentRow.get("workspaceRoles").get(0).get("role").get("name").asText()).isEqualTo(BuiltInRoles.AGENT);
        assertThat(agentRow.has("password")).isFalse();
        assertThat(agentRow.has("tokenVersion")).isFalse();
        assertThat(byUsername(list, "wm").get("manageable").asBoolean()).isFalse();

        // a workspace of another customer, or one without access, is not found
        send(get(usersOf(brandB)), wmToken, null).andExpect(status().isNotFound());
        send(get(usersOf(globexShop)), bossToken, null).andExpect(status().isNotFound());
    }

    @Test
    void workspaceManagerCreatesAgentWithMustChangePassword() throws Exception {
        send(post(usersOf(brandA)), wmToken, newUser("NewAgent", BuiltInRoles.AGENT))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.user.username").value("newagent"))
                .andExpect(jsonPath("$.user.role").isEmpty())
                .andExpect(jsonPath("$.user.workspaceRoles[0].workspaceName").value("Brand A"))
                .andExpect(jsonPath("$.user.workspaceRoles[0].role.name").value(BuiltInRoles.AGENT))
                .andExpect(jsonPath("$.user.mustChangePassword").value(true))
                .andExpect(jsonPath("$.user.password").doesNotExist())
                .andExpect(jsonPath("$.temporaryPassword").value(TEMP));

        User created = users.findByUsername("newagent").orElseThrow();
        assertThat(created.getCustomerId()).isEqualTo(acme.getId());
        assertThat(created.getRoleId()).isNull();
        assertThat(created.getWorkspaceRoles()).containsExactly(role(brandA, BuiltInRoles.AGENT));
        assertThat(created.isMustChangePassword()).isTrue();
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("username", "newagent", "password", TEMP))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passwordChangeRequired").value(true));

        send(post(usersOf(brandA)), wmToken, newUser("NEWAGENT", BuiltInRoles.AGENT))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE"))
                .andExpect(jsonPath("$.field").value("username"));
        send(post(usersOf(brandA)), wmToken, newUser("other", BuiltInRoles.CUSTOMER_ADMIN))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.roleId").exists());
        Map<String, Object> noRole = newUser("other", BuiltInRoles.AGENT);
        noRole.remove("roleId");
        send(post(usersOf(brandA)), wmToken, noRole)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.roleId").exists());
        assertThat(users.findByUsername("other")).isEmpty();
    }

    @Test
    void workspaceManagerCannotCreateWorkspaceManager() throws Exception {
        send(post(usersOf(brandA)), wmToken, newUser("newwm", BuiltInRoles.WORKSPACE_MANAGER))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        assertThat(users.findByUsername("newwm")).isEmpty();
    }

    @Test
    void customerAdminCreatesWorkspaceManager() throws Exception {
        send(post(usersOf(brandB)), bossToken, newUser("newwm", BuiltInRoles.WORKSPACE_MANAGER))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.user.workspaceRoles[0].workspaceId").value(brandB.getId()))
                .andExpect(jsonPath("$.user.workspaceRoles[0].role.name").value(BuiltInRoles.WORKSPACE_MANAGER));
        User created = users.findByUsername("newwm").orElseThrow();
        assertThat(created.getWorkspaceRoles()).containsExactly(role(brandB, BuiltInRoles.WORKSPACE_MANAGER));
        assertThat(created.getCustomerId()).isEqualTo(acme.getId());
    }

    @Test
    void addExistingCompanyUserToSecondWorkspace() throws Exception {
        send(post(usersOf(brandB)), bossToken, existing(agent, BuiltInRoles.TEAM_LEAD))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.user.id").value(agent.getId()))
                .andExpect(jsonPath("$.temporaryPassword").doesNotExist());
        assertThat(users.findById(agent.getId()).orElseThrow().getWorkspaceRoles())
                .containsExactly(role(brandA, BuiltInRoles.AGENT), role(brandB, BuiltInRoles.TEAM_LEAD));

        send(post(usersOf(brandB)), bossToken, existing(agent, BuiltInRoles.AGENT))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_IN_WORKSPACE"));
        send(post(usersOf(brandA)), bossToken, existing(gboss, BuiltInRoles.AGENT))
                .andExpect(status().isNotFound());
        send(post(usersOf(brandA)), bossToken, Map.of("userId", "nope", "roleId", testUsers.roleId(BuiltInRoles.AGENT)))
                .andExpect(status().isNotFound());
        assertThat(users.findById(gboss.getId()).orElseThrow().getWorkspaceRoles()).isEmpty();
    }

    @Test
    void cannotGiveBrandRoleToCustomerAdmin() throws Exception {
        User boss2 = testUsers.createInCustomer(acme.getId(), "boss2", BuiltInRoles.CUSTOMER_ADMIN, List.of());
        send(post(usersOf(brandA)), bossToken, existing(boss2, BuiltInRoles.AGENT))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ALREADY_ALL_BRANDS"));
        Map<String, Object> change = new HashMap<>();
        change.put("name", boss2.getName());
        change.put("roleId", testUsers.roleId(BuiltInRoles.AGENT));
        send(put(usersOf(brandA) + "/" + boss2.getId()), bossToken, change)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ALREADY_ALL_BRANDS"));
        assertThat(users.findById(boss2.getId()).orElseThrow().getWorkspaceRoles()).isEmpty();
    }

    @Test
    void teamLeadCanViewButNotCreate() throws Exception {
        send(get(usersOf(brandA)), leadToken, null).andExpect(status().isOk());
        send(post(usersOf(brandA)), leadToken, newUser("newagent", BuiltInRoles.AGENT))
                .andExpect(status().isForbidden());
        send(get("/api/workspaces/" + brandA.getId() + "/assignable-roles"), leadToken, null)
                .andExpect(status().isForbidden());
        assertThat(users.findByUsername("newagent")).isEmpty();
    }

    @Test
    void workspaceManagerCannotResetPasswordOfAgentAlsoInOtherWorkspace() throws Exception {
        User both = testUsers.createInCustomer(acme.getId(), "both", null,
                List.of(role(brandA, BuiltInRoles.AGENT), role(brandB, BuiltInRoles.AGENT)));
        send(post(usersOf(brandA) + "/" + both.getId() + "/reset-password"), wmToken, Map.of("temporaryPassword", TEMP))
                .andExpect(status().isForbidden());
        assertThat(users.findById(both.getId()).orElseThrow().isMustChangePassword()).isFalse();
        assertThat(byUsername(json(send(get(usersOf(brandA)), wmToken, null)), "both").get("manageable").asBoolean())
                .isFalse();

        send(post(usersOf(brandA) + "/" + agent.getId() + "/reset-password"), wmToken, Map.of("temporaryPassword", TEMP))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.temporaryPassword").value(TEMP))
                .andExpect(jsonPath("$.user").doesNotExist());
        assertThat(users.findById(agent.getId()).orElseThrow().isMustChangePassword()).isTrue();
    }

    @Test
    void workspaceManagerUpdatesDetailsAndRoleWithinLimits() throws Exception {
        Map<String, Object> change = new HashMap<>();
        change.put("name", "Agent Renamed");
        change.put("email", "a@example.com");
        change.put("roleId", testUsers.roleId(BuiltInRoles.TEAM_LEAD));
        send(put(usersOf(brandA) + "/" + agent.getId()), wmToken, change)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Agent Renamed"))
                .andExpect(jsonPath("$.workspaceRoles[0].role.name").value(BuiltInRoles.TEAM_LEAD));
        User saved = users.findById(agent.getId()).orElseThrow();
        assertThat(saved.getEmail()).isEqualTo("a@example.com");
        assertThat(saved.getWorkspaceRoles()).containsExactly(role(brandA, BuiltInRoles.TEAM_LEAD));

        change.put("roleId", testUsers.roleId(BuiltInRoles.WORKSPACE_MANAGER));
        send(put(usersOf(brandA) + "/" + agent.getId()), wmToken, change).andExpect(status().isForbidden());
        // changing another Workspace Manager's role is refused, even to a lower role
        User wm2 = inA("wm2", BuiltInRoles.WORKSPACE_MANAGER);
        Map<String, Object> demote = new HashMap<>();
        demote.put("name", wm2.getName());
        demote.put("roleId", testUsers.roleId(BuiltInRoles.AGENT));
        send(put(usersOf(brandA) + "/" + wm2.getId()), wmToken, demote).andExpect(status().isForbidden());
        assertThat(users.findById(wm2.getId()).orElseThrow().getWorkspaceRoles())
                .containsExactly(role(brandA, BuiltInRoles.WORKSPACE_MANAGER));
        assertThat(users.findById(agent.getId()).orElseThrow().getWorkspaceRoles())
                .containsExactly(role(brandA, BuiltInRoles.TEAM_LEAD));
    }

    @Test
    void userOutsideTheWorkspaceIs404() throws Exception {
        User bOnly = testUsers.createInCustomer(acme.getId(), "bonly", null, List.of(role(brandB, BuiltInRoles.AGENT)));
        Map<String, Object> change = Map.of("name", "X", "roleId", testUsers.roleId(BuiltInRoles.AGENT));
        send(put(usersOf(brandA) + "/" + bOnly.getId()), bossToken, change).andExpect(status().isNotFound());
        send(delete(usersOf(brandA) + "/" + bOnly.getId()), bossToken, null).andExpect(status().isNotFound());
        send(post(usersOf(brandA) + "/" + gboss.getId() + "/reset-password"), bossToken,
                Map.of("temporaryPassword", TEMP)).andExpect(status().isNotFound());
        send(put(usersOf(brandB) + "/" + agent.getId()), wmToken, change).andExpect(status().isNotFound());
        assertThat(users.findById(bOnly.getId()).orElseThrow().getName()).isEqualTo("Test bonly");
    }

    @Test
    void removeFromWorkspaceAlsoLeavesTeams() throws Exception {
        User both = testUsers.createInCustomer(acme.getId(), "both", null,
                List.of(role(brandA, BuiltInRoles.AGENT), role(brandB, BuiltInRoles.AGENT)));
        Team general = team(brandA, "General", both.getId(), agent.getId());
        Team returns = team(brandA, "Returns", both.getId());
        Team bTeam = team(brandB, "General", both.getId());

        send(delete(usersOf(brandA) + "/" + both.getId()), wmToken, null).andExpect(status().isNoContent());

        assertThat(users.findById(both.getId()).orElseThrow().getWorkspaceRoles())
                .containsExactly(role(brandB, BuiltInRoles.AGENT));
        assertThat(teams.findById(general.getId()).orElseThrow().getMemberIds()).containsExactly(agent.getId());
        assertThat(teams.findById(returns.getId()).orElseThrow().getMemberIds()).isEmpty();
        assertThat(teams.findById(bTeam.getId()).orElseThrow().getMemberIds()).containsExactly(both.getId());
        send(delete(usersOf(brandA) + "/" + wm.getId()), wmToken, null).andExpect(status().isForbidden());
    }

    @Test
    void removingLastAccessIs400() throws Exception {
        send(delete(usersOf(brandA) + "/" + agent.getId()), wmToken, null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("LAST_ACCESS"));
        assertThat(users.findById(agent.getId()).orElseThrow().getWorkspaceRoles())
                .containsExactly(role(brandA, BuiltInRoles.AGENT));
    }

    @Test
    void assignableRolesForWorkspaceManagerAreTeamLeadAndAgent() throws Exception {
        JsonNode roles = json(send(get("/api/workspaces/" + brandA.getId() + "/assignable-roles"), wmToken, null)
                .andExpect(status().isOk()));
        assertThat(StreamSupport.stream(roles.spliterator(), false).map(r -> r.get("name").asText()).toList())
                .containsExactly(BuiltInRoles.AGENT, BuiltInRoles.TEAM_LEAD);
        JsonNode bossRoles = json(send(get("/api/workspaces/" + brandB.getId() + "/assignable-roles"), bossToken, null)
                .andExpect(status().isOk()));
        assertThat(StreamSupport.stream(bossRoles.spliterator(), false).map(r -> r.get("name").asText()).toList())
                .containsExactly(BuiltInRoles.AGENT, BuiltInRoles.TEAM_LEAD, BuiltInRoles.WORKSPACE_MANAGER);
        send(get("/api/workspaces/" + brandB.getId() + "/assignable-roles"), wmToken, null)
                .andExpect(status().isNotFound());
    }

    @Test
    void customerAdminDeactivatesAgent() throws Exception {
        String agentToken = testUsers.accessToken(agent);
        send(get("/api/users/me"), agentToken, null).andExpect(status().isOk());

        send(post("/api/users/" + agent.getId() + "/disable"), bossToken, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
        assertThat(users.findById(agent.getId()).orElseThrow().isActive()).isFalse();
        send(get("/api/users/me"), agentToken, null).andExpect(status().isUnauthorized());

        send(post("/api/users/" + agent.getId() + "/enable"), bossToken, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true));
        assertThat(users.findById(agent.getId()).orElseThrow().isActive()).isTrue();

        // a Workspace Manager lacks company-user:manage
        send(post("/api/users/" + agent.getId() + "/disable"), wmToken, null).andExpect(status().isForbidden());
    }

    @Test
    void customerAdminCannotDeactivateAnotherCustomerAdmin() throws Exception {
        User boss2 = testUsers.createInCustomer(acme.getId(), "boss2", BuiltInRoles.CUSTOMER_ADMIN, List.of());
        send(post("/api/users/" + boss2.getId() + "/disable"), bossToken, null)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        assertThat(users.findById(boss2.getId()).orElseThrow().isActive()).isTrue();
    }

    @Test
    void deactivateUserOfOtherCustomerIs404() throws Exception {
        send(post("/api/users/" + gboss.getId() + "/disable"), bossToken, null)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        send(post("/api/users/nope/disable"), bossToken, null).andExpect(status().isNotFound());
        // platform staff never reach customer users through the company endpoint
        String amToken = testUsers.accessToken(testUsers.create("am", "pw", BuiltInRoles.ACCOUNT_MANAGER, null));
        send(post("/api/users/" + agent.getId() + "/disable"), amToken, null).andExpect(status().isNotFound());
        assertThat(users.findById(gboss.getId()).orElseThrow().isActive()).isTrue();
        assertThat(users.findById(agent.getId()).orElseThrow().isActive()).isTrue();
    }

    @Test
    void customerAdminDisablesAtCompanyLevel() throws Exception {
        send(post("/api/users/" + agent.getId() + "/disable"), bossToken, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.disabled.by").value("boss"))
                .andExpect(jsonPath("$.disabled.level").value("COMPANY"));
    }

    @Test
    void customerAdminCannotEnableLoginPlatformDisabled() throws Exception {
        String amToken = testUsers.accessToken(testUsers.create("am", "pw", BuiltInRoles.ACCOUNT_MANAGER, null));
        send(post("/api/customers/" + acme.getId() + "/users/" + agent.getId() + "/disable"), amToken, null)
                .andExpect(status().isOk());

        send(post("/api/users/" + agent.getId() + "/enable"), bossToken, null)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("DISABLED_AT_HIGHER_LEVEL"))
                .andExpect(jsonPath("$.level").value("PLATFORM"));
        assertThat(users.findById(agent.getId()).orElseThrow().isActive()).isFalse();
    }
}
