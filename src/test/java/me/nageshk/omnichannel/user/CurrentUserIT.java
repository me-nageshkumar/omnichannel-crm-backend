package me.nageshk.omnichannel.user;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Set;
import me.nageshk.omnichannel.access.Role;
import me.nageshk.omnichannel.access.RoleCache;
import me.nageshk.omnichannel.access.RoleRepository;
import me.nageshk.omnichannel.support.TestUsers;
import me.nageshk.omnichannel.tenant.Customer;
import me.nageshk.omnichannel.tenant.Workspace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest
@AutoConfigureMockMvc
class CurrentUserIT {

    @Autowired MockMvc mvc;
    @Autowired TestUsers testUsers;
    @Autowired UserRepository repo;
    @Autowired RoleRepository roles;
    @Autowired RoleCache roleCache;

    @BeforeEach
    void clean() {
        testUsers.deleteAll();
    }

    private User superAdmin() {
        User u = testUsers.create("nageshk", "pw", "SUPER_ADMIN", TestUsers.TEST_SECRET);
        u.setName("Nagesh Kumar");
        u.setEmail("me@example.in");
        u.setMobileNumber("9928088743");
        return repo.save(u);
    }

    private ResultActions getMe(String token) throws Exception {
        return mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + token));
    }

    @Test
    void returnsProfileOfCaller() throws Exception {
        User u = superAdmin();
        getMe(testUsers.accessToken(u))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(u.getId()))
                .andExpect(jsonPath("$.username").value("nageshk"))
                .andExpect(jsonPath("$.name").value("Nagesh Kumar"))
                .andExpect(jsonPath("$.email").value("me@example.in"))
                .andExpect(jsonPath("$.mobileNumber").value("9928088743"))
                .andExpect(jsonPath("$.role.name").value("SUPER_ADMIN"))
                .andExpect(jsonPath("$.role.id").value(u.getRoleId()))
                .andExpect(jsonPath("$.twoFactorEnabled").value(true))
                .andExpect(jsonPath("$.mustChangePassword").value(false));
    }

    @Test
    void superAdminShape() throws Exception {
        getMe(testUsers.accessToken(superAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.superAdmin").value(true))
                .andExpect(jsonPath("$.customer").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.workspaces", hasSize(0)))
                .andExpect(jsonPath("$.authorities", hasItem("platform-user:manage")))
                .andExpect(jsonPath("$.authorities", not(hasItem("SUPER_ADMIN"))));
    }

    @Test
    void customerAdminSeesEveryWorkspaceWithNullRole() throws Exception {
        Customer c = testUsers.customer("Acme");
        Workspace a = testUsers.workspace(c.getId(), "Brand A");
        Workspace b = testUsers.workspace(c.getId(), "Brand B");
        User admin = testUsers.createInCustomer(c.getId(), "boss", "CUSTOMER_ADMIN", List.of());

        getMe(testUsers.accessToken(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.superAdmin").value(false))
                .andExpect(jsonPath("$.customer.id").value(c.getId()))
                .andExpect(jsonPath("$.customer.name").value("Acme"))
                .andExpect(jsonPath("$.role.name").value("CUSTOMER_ADMIN"))
                .andExpect(jsonPath("$.authorities", hasItem("company-user:manage")))
                .andExpect(jsonPath("$.workspaces", hasSize(2)))
                .andExpect(jsonPath("$.workspaces[0].id").value(a.getId()))
                .andExpect(jsonPath("$.workspaces[0].name").value("Brand A"))
                .andExpect(jsonPath("$.workspaces[0].role").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.workspaces[0].authorities", hasItem("user:manage")))
                .andExpect(jsonPath("$.workspaces[1].id").value(b.getId()));
    }

    @Test
    void workspaceManagerSeesOnlyItsWorkspaceWithRoleAndAuthorities() throws Exception {
        Customer c = testUsers.customer("Acme");
        Workspace a = testUsers.workspace(c.getId(), "Brand A");
        testUsers.workspace(c.getId(), "Brand B");
        String wmId = testUsers.roleId("WORKSPACE_MANAGER");
        User wm = testUsers.createInCustomer(c.getId(), "wm", null, List.of(new WorkspaceRole(a.getId(), wmId)));

        getMe(testUsers.accessToken(wm))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.authorities", hasSize(0)))
                .andExpect(jsonPath("$.workspaces", hasSize(1)))
                .andExpect(jsonPath("$.workspaces[0].id").value(a.getId()))
                .andExpect(jsonPath("$.workspaces[0].role.id").value(wmId))
                .andExpect(jsonPath("$.workspaces[0].role.name").value("WORKSPACE_MANAGER"))
                .andExpect(jsonPath("$.workspaces[0].authorities", hasItem("user:manage")));
    }

    @Test
    void authoritiesReflectRoleEditWithoutNewToken() throws Exception {
        Customer c = testUsers.customer("Acme");
        Workspace a = testUsers.workspace(c.getId(), "Brand A");
        String wmId = testUsers.roleId("WORKSPACE_MANAGER");
        User wm = testUsers.createInCustomer(c.getId(), "wm", null, List.of(new WorkspaceRole(a.getId(), wmId)));
        String token = testUsers.accessToken(wm);

        Role role = roles.findById(wmId).orElseThrow();
        role.setAuthorities(Set.of("user:view"));
        roles.save(role);
        roleCache.evictAll();

        getMe(token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workspaces[0].authorities", containsInAnyOrder("user:view")));
    }

    @Test
    void neverExposesSecrets() throws Exception {
        getMe(testUsers.accessToken(superAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.tokenVersion").doesNotExist())
                .andExpect(jsonPath("$.twoFactor").doesNotExist())
                .andExpect(jsonPath("$.totpSecret").doesNotExist());
    }

    @Test
    void reflectsTwoFactorChangeWithoutNewToken() throws Exception {
        User u = testUsers.create("dev", "pw", "WHATSAPP_DEVICE", null);
        String token = testUsers.accessToken(u);
        u.setTwoFactor(TwoFactor.enabledWith(TestUsers.TEST_SECRET));
        repo.save(u);

        getMe(token).andExpect(status().isOk()).andExpect(jsonPath("$.twoFactorEnabled").value(true));
    }

    @Test
    void requiresToken() throws Exception {
        mvc.perform(get("/api/users/me")).andExpect(status().isUnauthorized());
    }
}
