package me.nageshk.omnichannel.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import me.nageshk.omnichannel.access.BuiltInRoles;
import me.nageshk.omnichannel.support.TestUsers;
import me.nageshk.omnichannel.user.User;
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
class TeamsApiIT {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired TestUsers testUsers;
    @Autowired TeamRepository teams;

    Customer acme;
    Workspace brandA;
    Workspace brandB;
    Customer globex;
    User boss;
    User wm;
    User lead;
    User agent;
    User bOnly;
    User gboss;
    String bossToken;
    String wmToken;
    String leadToken;

    @BeforeEach
    void setUp() {
        testUsers.deleteAll();
        acme = testUsers.customer("Acme");
        brandA = testUsers.workspace(acme.getId(), "Brand A");
        brandB = testUsers.workspace(acme.getId(), "Brand B");
        globex = testUsers.customer("Globex");
        boss = testUsers.createInCustomer(acme.getId(), "boss", BuiltInRoles.CUSTOMER_ADMIN, List.of());
        wm = inA("wm", BuiltInRoles.WORKSPACE_MANAGER);
        lead = inA("lead", BuiltInRoles.TEAM_LEAD);
        agent = inA("agent", BuiltInRoles.AGENT);
        bOnly = testUsers.createInCustomer(acme.getId(), "bonly", null,
                List.of(new WorkspaceRole(brandB.getId(), testUsers.roleId(BuiltInRoles.AGENT))));
        gboss = testUsers.createInCustomer(globex.getId(), "gboss", BuiltInRoles.CUSTOMER_ADMIN, List.of());
        bossToken = testUsers.accessToken(boss);
        wmToken = testUsers.accessToken(wm);
        leadToken = testUsers.accessToken(lead);
    }

    private User inA(String username, String roleName) {
        return testUsers.createInCustomer(acme.getId(), username, null,
                List.of(new WorkspaceRole(brandA.getId(), testUsers.roleId(roleName))));
    }

    private String teamsOf(Workspace w) {
        return "/api/workspaces/" + w.getId() + "/teams";
    }

    private ResultActions send(MockHttpServletRequestBuilder req, String token, Object body) throws Exception {
        req = req.header("Authorization", "Bearer " + token);
        if (body != null) req = req.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body));
        return mvc.perform(req);
    }

    private Team team(Workspace w, String name, String... memberIds) {
        Team t = new Team();
        t.setCustomerId(w.getCustomerId());
        t.setWorkspaceId(w.getId());
        t.setName(name);
        t.setMemberIds(new ArrayList<>(List.of(memberIds)));
        return teams.save(t);
    }

    @Test
    void listsTeamsWithMembers() throws Exception {
        team(brandA, "Sales", agent.getId(), wm.getId());
        team(brandA, "Returns");
        team(brandB, "Other", bOnly.getId());

        ResultActions result = send(get(teamsOf(brandA)), leadToken, null).andExpect(status().isOk());
        JsonNode list = mapper.readTree(result.andReturn().getResponse().getContentAsString());
        assertThat(list).hasSize(2);
        assertThat(list.get(0).get("name").asText()).isEqualTo("Returns");
        assertThat(list.get(0).get("members")).isEmpty();
        JsonNode sales = list.get(1);
        assertThat(sales.get("members")).hasSize(2);
        JsonNode first = sales.get("members").get(0);
        assertThat(first.get("id").asText()).isEqualTo(agent.getId());
        assertThat(first.get("name").asText()).isEqualTo("Test agent");
        assertThat(first.get("username").asText()).isEqualTo("agent");
        assertThat(first.size()).isEqualTo(3);
        send(get(teamsOf(brandB)), leadToken, null).andExpect(status().isNotFound());
    }

    @Test
    void createsTeam() throws Exception {
        send(post(teamsOf(brandA)), wmToken, Map.of("name", " Support "))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Support"))
                .andExpect(jsonPath("$.members").isEmpty());
        Team saved = teams.findByWorkspaceIdOrderByName(brandA.getId()).get(0);
        assertThat(saved.getName()).isEqualTo("Support");
        assertThat(saved.getCustomerId()).isEqualTo(acme.getId());
        send(post(teamsOf(brandA)), wmToken, Map.of("name", " "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION"))
                .andExpect(jsonPath("$.fields.name").exists());
    }

    @Test
    void duplicateTeamNameIs409() throws Exception {
        Team sales = team(brandA, "Sales");
        Team other = team(brandA, "Other");
        team(brandB, "Support");
        send(post(teamsOf(brandA)), wmToken, Map.of("name", "Sales"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE"))
                .andExpect(jsonPath("$.field").value("name"));
        send(put(teamsOf(brandA) + "/" + other.getId()), wmToken, Map.of("name", "Sales", "memberIds", List.of()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.field").value("name"));
        // same name in another workspace is fine
        send(post(teamsOf(brandA)), wmToken, Map.of("name", "Support")).andExpect(status().isCreated());
        // keeping its own name is fine
        send(put(teamsOf(brandA) + "/" + sales.getId()), wmToken, Map.of("name", "Sales", "memberIds", List.of()))
                .andExpect(status().isOk());
    }

    @Test
    void setsMembersIncludingCustomerAdmin() throws Exception {
        Team t = team(brandA, "Sales", lead.getId());
        send(put(teamsOf(brandA) + "/" + t.getId()), wmToken,
                Map.of("name", "Sales 2", "memberIds", List.of(agent.getId(), boss.getId(), agent.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Sales 2"))
                .andExpect(jsonPath("$.members.length()").value(2))
                .andExpect(jsonPath("$.members[0].id").value(agent.getId()))
                .andExpect(jsonPath("$.members[1].username").value("boss"));
        assertThat(teams.findById(t.getId()).orElseThrow().getMemberIds()).containsExactly(agent.getId(), boss.getId());
    }

    @Test
    void memberWithoutAccessIs400() throws Exception {
        Team t = team(brandA, "Sales", agent.getId());
        for (String id : List.of(bOnly.getId(), gboss.getId(), "nope")) {
            send(put(teamsOf(brandA) + "/" + t.getId()), wmToken,
                    Map.of("name", "Sales", "memberIds", List.of(agent.getId(), id)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("NOT_IN_WORKSPACE"));
        }
        assertThat(teams.findById(t.getId()).orElseThrow().getMemberIds()).containsExactly(agent.getId());
    }

    @Test
    void nullMemberIdIs400() throws Exception {
        Team t = team(brandA, "Sales", agent.getId());
        java.util.Map<String, Object> body = new java.util.HashMap<>();
        body.put("name", "Sales");
        body.put("memberIds", java.util.Arrays.asList((String) null));
        send(put(teamsOf(brandA) + "/" + t.getId()), wmToken, body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("NOT_IN_WORKSPACE"));
        assertThat(teams.findById(t.getId()).orElseThrow().getMemberIds()).containsExactly(agent.getId());
    }

    @Test
    void teamOfOtherWorkspaceIs404() throws Exception {
        Team t = team(brandB, "Other");
        send(put(teamsOf(brandA) + "/" + t.getId()), bossToken, Map.of("name", "X", "memberIds", List.of()))
                .andExpect(status().isNotFound());
        send(put(teamsOf(brandA) + "/nope"), bossToken, Map.of("name", "X", "memberIds", List.of()))
                .andExpect(status().isNotFound());
        assertThat(teams.findById(t.getId()).orElseThrow().getName()).isEqualTo("Other");
    }

    @Test
    void teamLeadCannotManageTeams() throws Exception {
        Team t = team(brandA, "Sales");
        send(post(teamsOf(brandA)), leadToken, Map.of("name", "New")).andExpect(status().isForbidden());
        send(put(teamsOf(brandA) + "/" + t.getId()), leadToken, Map.of("name", "X", "memberIds", List.of()))
                .andExpect(status().isForbidden());
        assertThat(teams.findById(t.getId()).orElseThrow().getName()).isEqualTo("Sales");
    }
}
