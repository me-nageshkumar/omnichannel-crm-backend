package me.nageshk.omnichannel.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;
import me.nageshk.omnichannel.access.BuiltInRoles;
import me.nageshk.omnichannel.support.TestUsers;
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
class WorkspacesApiIT {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired TestUsers testUsers;
    @Autowired WorkspaceRepository workspaces;
    @Autowired TeamRepository teams;

    Customer acme;
    Workspace brandA;
    Workspace brandB;
    Workspace globexShop;
    String bossToken;
    String wmToken;
    String agentToken;
    String globexBossToken;

    @BeforeEach
    void setUp() {
        testUsers.deleteAll();
        acme = testUsers.customer("Acme");
        brandA = testUsers.workspace(acme.getId(), "Brand A");
        brandB = testUsers.workspace(acme.getId(), "Brand B");
        Customer globex = testUsers.customer("Globex");
        globexShop = testUsers.workspace(globex.getId(), "Globex Shop");
        bossToken = testUsers.accessToken(
                testUsers.createInCustomer(acme.getId(), "boss", BuiltInRoles.CUSTOMER_ADMIN, List.of()));
        wmToken = testUsers.accessToken(testUsers.createInCustomer(acme.getId(), "wm", null,
                List.of(new WorkspaceRole(brandA.getId(), testUsers.roleId(BuiltInRoles.WORKSPACE_MANAGER)))));
        agentToken = testUsers.accessToken(testUsers.createInCustomer(acme.getId(), "agent", null,
                List.of(new WorkspaceRole(brandA.getId(), testUsers.roleId(BuiltInRoles.AGENT)))));
        globexBossToken = testUsers.accessToken(
                testUsers.createInCustomer(globex.getId(), "gboss", BuiltInRoles.CUSTOMER_ADMIN, List.of()));
    }

    private ResultActions send(MockHttpServletRequestBuilder req, String token, Object body) throws Exception {
        if (token != null) req = req.header("Authorization", "Bearer " + token);
        if (body != null) req = req.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body));
        return mvc.perform(req);
    }

    private List<String> names(ResultActions result) throws Exception {
        JsonNode body = mapper.readTree(result.andReturn().getResponse().getContentAsString());
        return StreamSupport.stream(body.spliterator(), false).map(w -> w.get("name").asText()).toList();
    }

    @Test
    void customerAdminCreatesWorkspaceWithGeneralTeam() throws Exception {
        JsonNode body = mapper.readTree(send(post("/api/workspaces"), bossToken, Map.of("name", " Brand C "))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Brand C"))
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andReturn().getResponse().getContentAsString());

        Workspace created = workspaces.findById(body.get("id").asText()).orElseThrow();
        assertThat(created.getCustomerId()).isEqualTo(acme.getId());
        List<Team> ts = teams.findByWorkspaceIdOrderByName(created.getId());
        assertThat(ts).extracting(Team::getName).containsExactly("General");
        assertThat(ts.get(0).getCustomerId()).isEqualTo(acme.getId());
        assertThat(names(send(get("/api/workspaces"), bossToken, null)))
                .containsExactly("Brand A", "Brand B", "Brand C");

        send(post("/api/workspaces"), bossToken, Map.of("name", " "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION"));
    }

    @Test
    void duplicateWorkspaceNameIs409() throws Exception {
        long before = workspaces.count();
        send(post("/api/workspaces"), bossToken, Map.of("name", "Brand A"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE"))
                .andExpect(jsonPath("$.field").value("name"));
        assertThat(workspaces.count()).isEqualTo(before);
        send(put("/api/workspaces/" + brandB.getId()), bossToken, Map.of("name", "Brand A"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.field").value("name"));
        // the same name in another customer is fine
        send(post("/api/workspaces"), globexBossToken, Map.of("name", "Brand A")).andExpect(status().isCreated());
    }

    @Test
    void workspaceManagerCannotCreateWorkspace() throws Exception {
        long before = workspaces.count();
        send(post("/api/workspaces"), wmToken, Map.of("name", "Brand C"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        assertThat(workspaces.count()).isEqualTo(before);
    }

    @Test
    void workspaceManagerRenamesOwnWorkspace() throws Exception {
        send(put("/api/workspaces/" + brandA.getId()), wmToken, Map.of("name", "Brand Alpha"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(brandA.getId()))
                .andExpect(jsonPath("$.name").value("Brand Alpha"));
        assertThat(workspaces.findById(brandA.getId()).orElseThrow().getName()).isEqualTo("Brand Alpha");

        send(put("/api/workspaces/" + brandB.getId()), wmToken, Map.of("name", "Mine"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        send(put("/api/workspaces/" + globexShop.getId()), bossToken, Map.of("name", "Mine"))
                .andExpect(status().isNotFound());
        send(put("/api/workspaces/" + brandA.getId()), agentToken, Map.of("name", "Mine"))
                .andExpect(status().isForbidden());
        assertThat(workspaces.findById(brandB.getId()).orElseThrow().getName()).isEqualTo("Brand B");
        assertThat(workspaces.findById(globexShop.getId()).orElseThrow().getName()).isEqualTo("Globex Shop");
    }

    @Test
    void listShowsOnlyAccessibleWorkspaces() throws Exception {
        assertThat(names(send(get("/api/workspaces"), bossToken, null).andExpect(status().isOk())))
                .containsExactly("Brand A", "Brand B");
        assertThat(names(send(get("/api/workspaces"), wmToken, null))).containsExactly("Brand A");
        assertThat(names(send(get("/api/workspaces"), globexBossToken, null))).containsExactly("Globex Shop");
        assertThat(names(send(get("/api/workspaces"), testUsers.tokenFor("device"), null))).isEmpty();
    }
}
