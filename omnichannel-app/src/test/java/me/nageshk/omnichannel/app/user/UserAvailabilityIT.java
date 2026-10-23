package me.nageshk.omnichannel.app.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import me.nageshk.omnichannel.app.support.TestUsers;
import me.nageshk.omnichannel.db.tenant.Customer;
import me.nageshk.omnichannel.db.tenant.Workspace;
import me.nageshk.omnichannel.db.user.Availability;
import me.nageshk.omnichannel.db.user.User;
import me.nageshk.omnichannel.db.user.UserRepository;
import me.nageshk.omnichannel.db.user.WorkspaceRole;
import me.nageshk.omnichannel.security.access.BuiltInRoles;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/** An agent's own Available / Away switch (chat-assignment design §3.2). */
@SpringBootTest
@AutoConfigureMockMvc
class UserAvailabilityIT {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired TestUsers testUsers;
    @Autowired UserRepository users;

    User agent;
    String token;

    @BeforeEach
    void setUp() {
        testUsers.deleteAll();
        Customer acme = testUsers.customer("Acme");
        Workspace brand = testUsers.workspace(acme.getId(), "Brand A");
        agent = testUsers.createInCustomer(acme.getId(), "agent", null,
                List.of(new WorkspaceRole(brand.getId(), testUsers.roleId(BuiltInRoles.AGENT))));
        token = testUsers.accessToken(agent);
    }

    @AfterEach
    void tearDown() {
        testUsers.deleteAll();
    }

    @Test
    void meShowsAvailableByDefault() throws Exception {
        mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availability.status").value("AVAILABLE"));
    }

    @Test
    void putAvailabilityAwayThenMe() throws Exception {
        mvc.perform(put("/api/users/me/availability").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("status", "AWAY"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availability.status").value("AWAY"))
                .andExpect(jsonPath("$.availability.at").isNotEmpty());
        mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.availability.status").value("AWAY"));
        assertThat(users.findById(agent.getId()).orElseThrow().availabilityStatus())
                .isEqualTo(Availability.Status.AWAY);
    }

    @Test
    void putAvailabilityRejectsUnknownStatus() throws Exception {
        mvc.perform(put("/api/users/me/availability").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("status", "BUSY"))))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/users/me/availability").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }
}
