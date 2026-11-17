package me.nageshk.omnichannel.app.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.nageshk.omnichannel.app.support.TestBilling;
import me.nageshk.omnichannel.app.support.TestUsers;
import me.nageshk.omnichannel.db.tenant.Customer;
import me.nageshk.omnichannel.db.tenant.Workspace;
import me.nageshk.omnichannel.db.user.User;
import me.nageshk.omnichannel.security.access.BuiltInRoles;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** A customer's plan is the most any of its users can hold, whatever their roles say. */
@SpringBootTest
@AutoConfigureMockMvc
class CeilingApiIT {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired TestUsers testUsers;
    @Autowired TestBilling billing;

    Customer customer;
    Workspace ws;
    String adminToken;

    @BeforeEach
    void setUp() {
        testUsers.deleteAll();
        billing.reset();
        customer = testUsers.customer("Acme");
        ws = testUsers.workspace(customer.getId(), "Main");
        User admin = testUsers.createInCustomer(customer.getId(), "ceil-admin", BuiltInRoles.CUSTOMER_ADMIN, List.of());
        adminToken = testUsers.accessToken(admin);
    }

    @Test
    void aCustomerWithoutAPlanIsAsBefore() throws Exception {
        JsonNode me = me(adminToken);
        assertThat(codes(me.at("/workspaces/0/authorities"))).contains("crm:view", "campaign:manage", "user:manage");
        assertThat(me.at("/subscription/state").asString()).isEqualTo("NONE");
        assertThat(me.at("/subscription/readOnly").asBoolean()).isFalse();
    }

    @Test
    void aPlatformUserHasNoSubscription() throws Exception {
        String am = testUsers.tokenFor("ceil-am");
        assertThat(me(am).get("subscription").isNull()).isTrue();
    }

    @Test
    void thePlanHoldsBackWhatItDoesNotInclude() throws Exception {
        billing.assign(customer.getId(), Set.of("campaigns"), Map.of());

        JsonNode me = me(adminToken);
        List<String> here = codes(me.at("/workspaces/0/authorities"));
        assertThat(here).contains("user:manage", "inbox:view", "campaign:manage", "workspace:create")
                .noneMatch(c -> c.startsWith("crm:")).doesNotContain("chatbot:manage", "ai:manage");
        assertThat(codes(me.get("authorities"))).contains("workspace:create", "billing:view");
        assertThat(me.at("/subscription/state").asString()).isEqualTo("ACTIVE");
        assertThat(me.at("/subscription/planName").asString()).isEqualTo("Test plan");
        assertThat(me.at("/subscription/zone").asString()).isEqualTo("Asia/Kolkata");

        mvc.perform(get("/api/workspaces/" + ws.getId() + "/crm/status").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/workspaces/" + ws.getId() + "/users").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
    }

    @Test
    void aPlanChangeAppliesOnTheNextRequestOfTheSameSession() throws Exception {
        billing.assign(customer.getId(), Set.of(), Map.of());
        mvc.perform(get("/api/workspaces/" + ws.getId() + "/crm/status").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isForbidden());

        billing.assign(customer.getId(), Set.of("crm"), Map.of());

        int after = mvc.perform(get("/api/workspaces/" + ws.getId() + "/crm/status")
                .header("Authorization", "Bearer " + adminToken)).andReturn().getResponse().getStatus();
        assertThat(after).isNotEqualTo(403);
        assertThat(codes(me(adminToken).at("/workspaces/0/authorities"))).contains("crm:view");
    }

    @Test
    void anAdminOnASmallPlanStillGivesAndChangesTheBuiltInRoles() throws Exception {
        billing.assign(customer.getId(), Set.of(), Map.of());
        String managerRole = testUsers.roleId(BuiltInRoles.WORKSPACE_MANAGER);

        String body = mvc.perform(post("/api/workspaces/" + ws.getId() + "/users")
                        .header("Authorization", "Bearer " + adminToken).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("name", "Mona", "username", "ceil-mona",
                                "email", "mona@example.com", "temporaryPassword", "temporary-pass-1",
                                "roleId", managerRole))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String userId = mapper.readTree(body).at("/user/id").asString();
        assertThat(userId).isNotBlank();

        // the new user holds the role, but nothing the plan leaves out
        mvc.perform(get("/api/workspaces/" + ws.getId() + "/users").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
        mvc.perform(put("/api/workspaces/" + ws.getId() + "/users/" + userId)
                        .header("Authorization", "Bearer " + adminToken).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("name", "Mona", "email", "mona@example.com",
                                "roleId", testUsers.roleId(BuiltInRoles.AGENT)))))
                .andExpect(status().isOk());
    }

    private JsonNode me(String token) throws Exception {
        return mapper.readTree(mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private static List<String> codes(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asString()));
        return out;
    }
}
