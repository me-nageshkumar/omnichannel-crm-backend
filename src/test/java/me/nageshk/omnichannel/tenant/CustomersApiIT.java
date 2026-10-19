package me.nageshk.omnichannel.tenant;

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
import java.util.Set;
import java.util.stream.StreamSupport;
import me.nageshk.omnichannel.access.BuiltInRoles;
import me.nageshk.omnichannel.access.Role;
import me.nageshk.omnichannel.access.RoleCache;
import me.nageshk.omnichannel.access.RoleLevel;
import me.nageshk.omnichannel.access.RoleRepository;
import me.nageshk.omnichannel.auth.LoginAttemptService;
import me.nageshk.omnichannel.support.TestUsers;
import me.nageshk.omnichannel.user.User;
import me.nageshk.omnichannel.user.UserRepository;
import me.nageshk.omnichannel.user.WorkspaceRole;
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
class CustomersApiIT {

    private static final String TEMP = "temporary-pass-1";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired TestUsers testUsers;
    @Autowired UserRepository users;
    @Autowired CustomerRepository customers;
    @Autowired WorkspaceRepository workspaces;
    @Autowired TeamRepository teams;
    @Autowired RoleRepository roles;
    @Autowired RoleCache roleCache;
    @Autowired LoginAttemptService attempts;

    User am;
    String amToken;

    @BeforeEach
    void setUp() {
        testUsers.deleteAll();
        List.of("am", "owner", "boss", "second").forEach(attempts::clearLogin);
        am = testUsers.create("am", "pw", BuiltInRoles.ACCOUNT_MANAGER, null);
        amToken = testUsers.accessToken(am);
    }

    private ResultActions send(MockHttpServletRequestBuilder req, String token, Object body) throws Exception {
        if (token != null) req = req.header("Authorization", "Bearer " + token);
        if (body != null) req = req.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body));
        return mvc.perform(req);
    }

    private JsonNode json(ResultActions result) throws Exception {
        return mapper.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private Map<String, Object> admin(String username) {
        Map<String, Object> admin = new HashMap<>();
        admin.put("name", "Olive Owner");
        admin.put("username", username);
        admin.put("email", "olive@example.com");
        admin.put("mobileNumber", "+911234567890");
        admin.put("temporaryPassword", TEMP);
        return admin;
    }

    private Map<String, Object> newCustomer(String name, String workspaceName, String ownerUsername) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        if (workspaceName != null) body.put("workspaceName", workspaceName);
        body.put("admin", admin(ownerUsername));
        return body;
    }

    private ResultActions login(String username, String password) throws Exception {
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("username", username, "password", password))));
    }

    /** A customer with an active Customer Admin "boss" (password "pw"). */
    private User bossOf(Customer c) {
        return testUsers.createInCustomer(c.getId(), "boss", BuiltInRoles.CUSTOMER_ADMIN, List.of());
    }

    @Test
    void accountManagerCreatesCustomerWithWorkspaceTeamAndAdmin() throws Exception {
        JsonNode body = json(send(post("/api/customers"), amToken, newCustomer("Acme", null, "Owner"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.customer.name").value("Acme"))
                .andExpect(jsonPath("$.customer.active").value(true))
                .andExpect(jsonPath("$.user.username").value("owner"))
                .andExpect(jsonPath("$.user.role.name").value(BuiltInRoles.CUSTOMER_ADMIN))
                .andExpect(jsonPath("$.user.mustChangePassword").value(true))
                .andExpect(jsonPath("$.user.password").doesNotExist())
                .andExpect(jsonPath("$.temporaryPassword").value(TEMP)));

        Customer acme = customers.findById(body.get("customer").get("id").asText()).orElseThrow();
        assertThat(acme.getCreatedBy()).isEqualTo(am.getId());
        List<Workspace> ws = workspaces.findByCustomerIdOrderByName(acme.getId());
        assertThat(ws).extracting(Workspace::getName).containsExactly("Acme");
        List<Team> ts = teams.findByWorkspaceIdOrderByName(ws.get(0).getId());
        assertThat(ts).extracting(Team::getName).containsExactly("General");
        assertThat(ts.get(0).getCustomerId()).isEqualTo(acme.getId());

        User owner = users.findByUsername("owner").orElseThrow();
        assertThat(owner.getCustomerId()).isEqualTo(acme.getId());
        assertThat(owner.getRoleId()).isEqualTo(testUsers.roleId(BuiltInRoles.CUSTOMER_ADMIN));
        assertThat(owner.getWorkspaceRoles()).isEmpty();
        assertThat(owner.isMustChangePassword()).isTrue();
        assertThat(owner.isActive()).isTrue();

        JsonNode named = json(send(post("/api/customers"), amToken, newCustomer("Globex", "Globex Shoes", "gowner"))
                .andExpect(status().isCreated()));
        assertThat(workspaces.findByCustomerIdOrderByName(named.get("customer").get("id").asText()))
                .extracting(Workspace::getName).containsExactly("Globex Shoes");
    }

    @Test
    void createIsAllOrNothing() throws Exception {
        testUsers.create("taken", "pw", BuiltInRoles.ACCOUNT_MANAGER, null);
        long customersBefore = customers.count();
        long workspacesBefore = workspaces.count();
        long teamsBefore = teams.count();
        long usersBefore = users.count();

        send(post("/api/customers"), amToken, newCustomer("Acme", null, "Taken"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE"))
                .andExpect(jsonPath("$.field").value("username"));

        assertThat(customers.count()).isEqualTo(customersBefore);
        assertThat(workspaces.count()).isEqualTo(workspacesBefore);
        assertThat(teams.count()).isEqualTo(teamsBefore);
        assertThat(users.count()).isEqualTo(usersBefore);
    }

    @Test
    void invalidOwnerIsRejectedBeforeAnythingIsCreated() throws Exception {
        Map<String, Object> body = newCustomer("Acme", null, "owner");
        @SuppressWarnings("unchecked")
        Map<String, Object> owner = (Map<String, Object>) body.get("admin");
        owner.put("temporaryPassword", "short");
        send(post("/api/customers"), amToken, body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("WEAK_PASSWORD"));
        owner.put("temporaryPassword", TEMP);
        owner.put("name", " ");
        send(post("/api/customers"), amToken, body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION"))
                .andExpect(jsonPath("$.fields['admin.name']").exists());
        assertThat(customers.count()).isZero();
        assertThat(workspaces.count()).isZero();
    }

    @Test
    void listIsPagedAndSearchable() throws Exception {
        for (int i = 1; i <= 55; i++) testUsers.customer(String.format("Cust %02d", i));
        Customer zeta = testUsers.customer("Zeta");
        zeta.setActive(false);
        customers.save(zeta);

        JsonNode first = json(send(get("/api/customers"), amToken, null).andExpect(status().isOk()));
        assertThat(first.get("items").size()).isEqualTo(50);
        assertThat(first.get("total").asLong()).isEqualTo(56);
        assertThat(first.get("page").asInt()).isZero();
        assertThat(first.get("items").get(0).get("name").asText()).isEqualTo("Cust 01");

        JsonNode second = json(send(get("/api/customers").param("page", "1"), amToken, null).andExpect(status().isOk()));
        assertThat(second.get("items").size()).isEqualTo(6);
        assertThat(second.get("page").asInt()).isEqualTo(1);

        JsonNode search = json(send(get("/api/customers").param("q", "cust 0"), amToken, null).andExpect(status().isOk()));
        assertThat(search.get("total").asLong()).isEqualTo(9);

        JsonNode inactive = json(send(get("/api/customers").param("active", "false"), amToken, null).andExpect(status().isOk()));
        assertThat(inactive.get("total").asLong()).isEqualTo(1);
        assertThat(inactive.get("items").get(0).get("name").asText()).isEqualTo("Zeta");

        JsonNode activeZ = json(send(get("/api/customers").param("q", "zet").param("active", "true"), amToken, null).andExpect(status().isOk()));
        assertThat(activeZ.get("total").asLong()).isZero();

        JsonNode regex = json(send(get("/api/customers").param("q", ".*"), amToken, null).andExpect(status().isOk()));
        assertThat(regex.get("total").asLong()).isZero();
    }

    @Test
    void detailShowsWorkspacesAndAdmins() throws Exception {
        JsonNode created = json(send(post("/api/customers"), amToken, newCustomer("Acme", null, "owner"))
                .andExpect(status().isCreated()));
        String id = created.get("customer").get("id").asText();
        Workspace shoes = testUsers.workspace(id, "Acme Shoes");
        testUsers.createInCustomer(id, "agent", null,
                List.of(new WorkspaceRole(shoes.getId(), testUsers.roleId(BuiltInRoles.AGENT))));

        JsonNode detail = json(send(get("/api/customers/" + id), amToken, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customer.name").value("Acme"))
                .andExpect(jsonPath("$.admins[0].password").doesNotExist()));
        List<String> wsNames = StreamSupport.stream(detail.get("workspaces").spliterator(), false)
                .map(w -> w.get("name").asText()).toList();
        assertThat(wsNames).containsExactly("Acme", "Acme Shoes");
        assertThat(detail.get("admins").size()).isEqualTo(1);
        assertThat(detail.get("admins").get(0).get("username").asText()).isEqualTo("owner");
        assertThat(detail.get("admins").get(0).get("role").get("name").asText()).isEqualTo(BuiltInRoles.CUSTOMER_ADMIN);
    }

    @Test
    void renameCustomer() throws Exception {
        Customer acme = testUsers.customer("Acme");
        send(put("/api/customers/" + acme.getId()), amToken, Map.of("name", "Acme Corp"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Acme Corp"));
        assertThat(customers.findById(acme.getId()).orElseThrow().getName()).isEqualTo("Acme Corp");
    }

    @Test
    void deactivateLocksOutCustomerUsers() throws Exception {
        Customer acme = testUsers.customer("Acme");
        User boss = bossOf(acme);
        String bossToken = testUsers.accessToken(boss);
        send(get("/api/users/me"), bossToken, null).andExpect(status().isOk());

        send(post("/api/customers/" + acme.getId() + "/deactivate"), amToken, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));

        assertThat(customers.findById(acme.getId()).orElseThrow().isActive()).isFalse();
        assertThat(users.findById(boss.getId()).orElseThrow().isActive()).isTrue();
        send(get("/api/users/me"), bossToken, null).andExpect(status().isUnauthorized());
        login("boss", "pw").andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("account_inactive"));
    }

    @Test
    void reactivateRestoresLogin() throws Exception {
        Customer acme = testUsers.customer("Acme");
        bossOf(acme);
        send(post("/api/customers/" + acme.getId() + "/deactivate"), amToken, null).andExpect(status().isOk());
        login("boss", "pw").andExpect(status().isUnauthorized());

        send(post("/api/customers/" + acme.getId() + "/reactivate"), amToken, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true));
        login("boss", "pw").andExpect(status().isOk()).andExpect(jsonPath("$.token").isNotEmpty());
    }

    @Test
    void addSecondCustomerAdmin() throws Exception {
        Customer acme = testUsers.customer("Acme");
        bossOf(acme);
        send(post("/api/customers/" + acme.getId() + "/admins"), amToken, admin("Second"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.user.username").value("second"))
                .andExpect(jsonPath("$.user.role.name").value(BuiltInRoles.CUSTOMER_ADMIN))
                .andExpect(jsonPath("$.temporaryPassword").value(TEMP));

        User second = users.findByUsername("second").orElseThrow();
        assertThat(second.getCustomerId()).isEqualTo(acme.getId());
        assertThat(second.isMustChangePassword()).isTrue();
        JsonNode detail = json(send(get("/api/customers/" + acme.getId()), amToken, null));
        assertThat(detail.get("admins").size()).isEqualTo(2);
    }

    @Test
    void adminRoleMustBeACompanyRole() throws Exception {
        Customer acme = testUsers.customer("Acme");
        Map<String, Object> body = admin("second");
        body.put("roleId", testUsers.roleId(BuiltInRoles.AGENT));
        send(post("/api/customers/" + acme.getId() + "/admins"), amToken, body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION"))
                .andExpect(jsonPath("$.fields.roleId").exists());
        assertThat(users.findByUsername("second")).isEmpty();
    }

    @Test
    void customerCreateAloneCannotAddAdminToExistingCustomer() throws Exception {
        Role creator = new Role();
        creator.setName("CUSTOMER_CREATOR");
        creator.setLevel(RoleLevel.PLATFORM);
        creator.setAuthorities(Set.of("customer:view", "customer:create"));
        roles.save(creator);
        roleCache.evictAll();
        String token = testUsers.accessToken(testUsers.create("creator", "pw", "CUSTOMER_CREATOR", null));

        send(post("/api/customers"), token, newCustomer("Acme", null, "owner")).andExpect(status().isCreated());
        String id = customers.findAll().get(0).getId();
        send(post("/api/customers/" + id + "/admins"), token, admin("second")).andExpect(status().isForbidden());
        assertThat(users.findByUsername("second")).isEmpty();
        JsonNode roles = json(send(get("/api/customers/assignable-roles"), token, null).andExpect(status().isOk()));
        assertThat(StreamSupport.stream(roles.spliterator(), false).map(r -> r.get("name").asText()).toList())
                .containsExactly(BuiltInRoles.CUSTOMER_ADMIN);
    }

    @Test
    void accountManagerResetsAdminPassword() throws Exception {
        Customer acme = testUsers.customer("Acme");
        User boss = bossOf(acme);
        String bossToken = testUsers.accessToken(boss);

        send(post("/api/customers/" + acme.getId() + "/admins/" + boss.getId() + "/reset-password"), amToken,
                Map.of("temporaryPassword", TEMP))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.temporaryPassword").value(TEMP));

        send(get("/api/users/me"), bossToken, null).andExpect(status().isUnauthorized());
        assertThat(users.findById(boss.getId()).orElseThrow().isMustChangePassword()).isTrue();
        login("boss", TEMP).andExpect(status().isOk()).andExpect(jsonPath("$.passwordChangeRequired").value(true));
    }

    @Test
    void accountManagerDeactivatesAdmin() throws Exception {
        Customer acme = testUsers.customer("Acme");
        User boss = bossOf(acme);
        String bossToken = testUsers.accessToken(boss);

        send(post("/api/customers/" + acme.getId() + "/admins/" + boss.getId() + "/deactivate"), amToken, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
        send(get("/api/users/me"), bossToken, null).andExpect(status().isUnauthorized());
        assertThat(users.findById(boss.getId()).orElseThrow().isActive()).isFalse();

        send(post("/api/customers/" + acme.getId() + "/admins/" + boss.getId() + "/reactivate"), amToken, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true));
        login("boss", "pw").andExpect(status().isOk()).andExpect(jsonPath("$.token").isNotEmpty());
    }

    @Test
    void customerAdminCannotUseCustomersApi() throws Exception {
        Customer acme = testUsers.customer("Acme");
        String bossToken = testUsers.accessToken(bossOf(acme));
        send(get("/api/customers"), bossToken, null)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        send(get("/api/customers/" + acme.getId()), bossToken, null).andExpect(status().isForbidden());
        send(post("/api/customers"), bossToken, newCustomer("Evil", null, "evil")).andExpect(status().isForbidden());
        send(post("/api/customers/" + acme.getId() + "/admins"), bossToken, admin("second")).andExpect(status().isForbidden());
        send(post("/api/customers/" + acme.getId() + "/deactivate"), bossToken, null).andExpect(status().isForbidden());
        assertThat(customers.count()).isEqualTo(1);
        assertThat(users.findByUsername("second")).isEmpty();
    }

    @Test
    void unknownCustomerIs404() throws Exception {
        send(get("/api/customers/nope"), amToken, null)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        send(put("/api/customers/nope"), amToken, Map.of("name", "X")).andExpect(status().isNotFound());
        send(post("/api/customers/nope/deactivate"), amToken, null).andExpect(status().isNotFound());
        send(post("/api/customers/nope/admins"), amToken, admin("second")).andExpect(status().isNotFound());
        assertThat(users.findByUsername("second")).isEmpty();

        // an admin of another customer, or a non-admin user, is not found under this customer
        Customer acme = testUsers.customer("Acme");
        Customer globex = testUsers.customer("Globex");
        User globexBoss = testUsers.createInCustomer(globex.getId(), "gboss", BuiltInRoles.CUSTOMER_ADMIN, List.of());
        send(post("/api/customers/" + acme.getId() + "/admins/" + globexBoss.getId() + "/deactivate"), amToken, null)
                .andExpect(status().isNotFound());
        Workspace w = testUsers.workspace(acme.getId(), "Acme");
        User agent = testUsers.createInCustomer(acme.getId(), "agent", null,
                List.of(new WorkspaceRole(w.getId(), testUsers.roleId(BuiltInRoles.AGENT))));
        send(post("/api/customers/" + acme.getId() + "/admins/" + agent.getId() + "/reset-password"), amToken,
                Map.of("temporaryPassword", TEMP)).andExpect(status().isNotFound());
        assertThat(users.findById(globexBoss.getId()).orElseThrow().isActive()).isTrue();
    }
}
