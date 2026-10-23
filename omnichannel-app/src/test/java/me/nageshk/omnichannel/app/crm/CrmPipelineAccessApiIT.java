package me.nageshk.omnichannel.app.crm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import me.nageshk.omnichannel.db.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class CrmPipelineAccessApiIT extends CrmTestBase {

    String salesId;

    @BeforeEach
    void enable() throws Exception {
        enableCrm();
        JsonNode list = json(send(get(crm() + "/pipelines"), managerToken, null).andExpect(status().isOk()));
        salesId = list.get(0).get("id").asString();
    }

    private String url(User u) {
        return crm() + "/users/" + u.getId() + "/pipelines";
    }

    private JsonNode agentStatus() throws Exception {
        return json(send(get(crm() + "/status"), agentToken, null).andExpect(status().isOk()));
    }

    @Test
    void managerAssignsSalesToAgent() throws Exception {
        send(put(url(agent)), managerToken, Map.of("pipelineIds", List.of(salesId, salesId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(agent.getId()))
                .andExpect(jsonPath("$.pipelineIds.length()").value(1))
                .andExpect(jsonPath("$.pipelineIds[0]").value(salesId));
        JsonNode st = agentStatus();
        assertThat(st.get("pipelineAccess").size()).isEqualTo(1);
        assertThat(st.get("pipelineAccess").get(0).asString()).isEqualTo(salesId);
        JsonNode pipelines = json(send(get(crm() + "/pipelines"), agentToken, null).andExpect(status().isOk()));
        assertThat(pipelines.size()).isEqualTo(1);
        assertThat(pipelines.get(0).get("id").asString()).isEqualTo(salesId);
        send(get(url(agent)), managerToken, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.pipelineIds[0]").value(salesId));
    }

    @Test
    void agentWithNothingSeesNoPipelines() throws Exception {
        assertThat(agentStatus().get("pipelineAccess").size()).isZero();
        assertThat(json(send(get(crm() + "/pipelines"), agentToken, null).andExpect(status().isOk())).size()).isZero();
        send(get(crm() + "/pipelines/" + salesId), agentToken, null).andExpect(status().isNotFound());
        send(get(url(agent)), managerToken, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.pipelineIds.length()").value(0));
    }

    @Test
    void emptyListIsStoredAndRevokes() throws Exception {
        assignPipelines(agent, salesId);
        send(put(url(agent)), managerToken, Map.of("pipelineIds", List.of()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.pipelineIds.length()").value(0));
        assertThat(agentStatus().get("pipelineAccess").size()).isZero();
    }

    @Test
    void unknownPipelineRejected() throws Exception {
        send(put(url(agent)), managerToken, Map.of("pipelineIds", List.of("nope")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION"))
                .andExpect(jsonPath("$.fields.pipelineIds").exists());
    }

    @Test
    void userOfOtherBrandIs404() throws Exception {
        User stranger = testUsers.createInCustomer(acme.getId(), "stranger", null,
                List.of(new me.nageshk.omnichannel.db.user.WorkspaceRole(other.getId(), testUsers.roleId(
                        me.nageshk.omnichannel.security.access.BuiltInRoles.AGENT))));
        send(put(url(stranger)), managerToken, Map.of("pipelineIds", List.of(salesId))).andExpect(status().isNotFound());
        send(get(url(stranger)), managerToken, null).andExpect(status().isNotFound());
        send(put(crm() + "/users/missing/pipelines"), managerToken, Map.of("pipelineIds", List.of()))
                .andExpect(status().isNotFound());
    }

    @Test
    void agentCannotAssignOrReadOthers() throws Exception {
        send(put(url(agent)), agentToken, Map.of("pipelineIds", List.of(salesId))).andExpect(status().isForbidden());
        send(get(url(agent2)), agentToken, null).andExpect(status().isForbidden());
        assignPipelines(agent, salesId);
        send(get(url(agent)), agentToken, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.pipelineIds[0]").value(salesId));
    }

    @Test
    void deletingPipelinePullsItFromAssignments() throws Exception {
        JsonNode second = json(send(post(crm() + "/pipelines"), managerToken, Map.of("name", "Second"))
                .andExpect(status().is2xxSuccessful()));
        String secondId = second.get("id").asString();
        assignPipelines(agent, salesId, secondId);
        send(delete(crm() + "/pipelines/" + salesId), managerToken, null).andExpect(status().isNoContent());
        JsonNode view = json(send(get(url(agent)), managerToken, null).andExpect(status().isOk()));
        assertThat(view.get("pipelineIds").size()).isEqualTo(1);
        assertThat(view.get("pipelineIds").get(0).asString()).isEqualTo(secondId);
        // Deleting the last pipeline re-seeds Sales under the same id; the stale grant must not revive.
        send(delete(crm() + "/pipelines/" + secondId), managerToken, null).andExpect(status().isNoContent());
        send(get(crm() + "/pipelines"), managerToken, null).andExpect(status().isOk());
        assertThat(json(send(get(url(agent)), managerToken, null)).get("pipelineIds").size()).isZero();
        assertThat(agentStatus().get("pipelineAccess").size()).isZero();
    }

    @Test
    @Disabled("Task 10")
    void removingAccessHidesDealsImmediately() throws Exception {
        // Task 10: agent sees a deal; manager removes the assignment; agent's GET /deals/{id} and board -> 404.
    }
}
