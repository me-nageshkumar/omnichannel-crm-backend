package me.nageshk.omnichannel.app.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.Set;
import me.nageshk.omnichannel.app.support.TestBilling;
import me.nageshk.omnichannel.app.support.TestUsers;
import me.nageshk.omnichannel.common.billing.LimitKey;
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
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;

/** A plan's caps on users, brands and WhatsApp numbers. */
@SpringBootTest
@AutoConfigureMockMvc
class CustomerLimitsIT {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired TestUsers testUsers;
    @Autowired TestBilling billing;

    Customer customer;
    Workspace ws;
    String adminToken;
    int n;

    @BeforeEach
    void setUp() {
        testUsers.deleteAll();
        billing.reset();
        customer = testUsers.customer("Acme");
        ws = testUsers.workspace(customer.getId(), "Main");
        User admin = testUsers.createInCustomer(customer.getId(), "lim-admin", BuiltInRoles.CUSTOMER_ADMIN, List.of());
        adminToken = testUsers.accessToken(admin);
    }

    @Test
    void usersStopAtTheCap() throws Exception {
        billing.assign(customer.getId(), Set.of(), Map.of(LimitKey.USERS, 2));

        addUser().andExpect(status().isCreated());
        addUser().andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LIMIT_REACHED"))
                .andExpect(jsonPath("$.key").value("USERS"))
                .andExpect(jsonPath("$.limit").value("2"))
                .andExpect(jsonPath("$.used").value("2"));
    }

    @Test
    void aDisabledUserFreesItsPlaceAndCannotComeBackOverTheCap() throws Exception {
        billing.assign(customer.getId(), Set.of(), Map.of(LimitKey.USERS, 2));
        String first = idOf(addUser().andExpect(status().isCreated()));

        login(first, "disable").andExpect(status().isOk());
        addUser().andExpect(status().isCreated());
        login(first, "enable").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LIMIT_REACHED"));
    }

    @Test
    void loweringTheCapKeepsWhoIsThere() throws Exception {
        billing.assign(customer.getId(), Set.of(), Map.of(LimitKey.USERS, 3));
        addUser().andExpect(status().isCreated());
        addUser().andExpect(status().isCreated());

        billing.assign(customer.getId(), Set.of(), Map.of(LimitKey.USERS, 1));

        mvc.perform(get("/api/workspaces/" + ws.getId() + "/users").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(3));
        addUser().andExpect(status().isConflict()).andExpect(jsonPath("$.used").value("3"));
    }

    @Test
    void brandsAndNumbersStopAtTheirCaps() throws Exception {
        billing.assign(customer.getId(), Set.of(), Map.of(LimitKey.BRANDS, 1, LimitKey.PHONE_NUMBERS, 1));

        mvc.perform(post("/api/workspaces").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Second\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.key").value("BRANDS"));

        addNumber("919800000001").andExpect(status().isCreated());
        addNumber("919800000002").andExpect(status().isConflict())
                .andExpect(jsonPath("$.key").value("PHONE_NUMBERS"));
    }

    @Test
    void aCustomerWithoutAPlanHasNoCaps() throws Exception {
        addUser().andExpect(status().isCreated());
        addUser().andExpect(status().isCreated());
        mvc.perform(post("/api/workspaces").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Second\"}"))
                .andExpect(status().isCreated());
        addNumber("919800000003").andExpect(status().isCreated());
        addNumber("919800000004").andExpect(status().isCreated());
    }

    private ResultActions addUser() throws Exception {
        n++;
        return mvc.perform(post("/api/workspaces/" + ws.getId() + "/users")
                .header("Authorization", "Bearer " + adminToken).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("name", "User " + n, "username", "lim-user-" + n,
                        "temporaryPassword", "temporary-pass-1", "roleId", testUsers.roleId(BuiltInRoles.AGENT)))));
    }

    private ResultActions addNumber(String number) throws Exception {
        return mvc.perform(post("/api/workspaces/" + ws.getId() + "/phone-numbers")
                .header("Authorization", "Bearer " + adminToken).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("number", number))));
    }

    /** Disables or enables the whole login, which is what takes or frees a place. */
    private ResultActions login(String userId, String action) throws Exception {
        return mvc.perform(post("/api/users/" + userId + "/" + action)
                .header("Authorization", "Bearer " + adminToken));
    }

    private String idOf(ResultActions created) throws Exception {
        String id = mapper.readTree(created.andReturn().getResponse().getContentAsString()).at("/user/id").asString();
        assertThat(id).isNotBlank();
        return id;
    }
}
