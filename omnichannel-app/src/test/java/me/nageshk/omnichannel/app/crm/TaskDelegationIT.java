package me.nageshk.omnichannel.app.crm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.nageshk.omnichannel.crm.activity.ActivityType;
import me.nageshk.omnichannel.crm.activity.CrmActivity;
import me.nageshk.omnichannel.db.access.Role;
import me.nageshk.omnichannel.db.access.RoleRepository;
import me.nageshk.omnichannel.db.user.User;
import me.nageshk.omnichannel.db.user.WorkspaceRole;
import me.nageshk.omnichannel.security.access.RoleCache;
import me.nageshk.omnichannel.db.access.RoleLevel;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import tools.jackson.databind.JsonNode;

/** Delegation (spec E C1–C6): assigner fields, the assignee check, work rights, the Delegated list and count. */
class TaskDelegationIT extends CrmTestBase {

    @Autowired RoleRepository roles;
    @Autowired RoleCache roleCache;

    /** A brand user on a role with exactly these authorities. */
    User userWith(String username, String roleName, Set<String> authorities) {
        Role r = new Role();
        r.setName(roleName);
        r.setLevel(RoleLevel.BRAND);
        r.setAuthorities(authorities);
        r = roles.save(r);
        roleCache.evictAll();
        return testUsers.createInCustomer(acme.getId(), username, null,
                List.of(new WorkspaceRole(brand.getId(), r.getId())));
    }

    Map<String, Object> task(String type, String id, String title, String assigneeId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("title", title);
        m.put("target", Map.of("type", type, "id", id));
        m.put("dueAt", Instant.now().plus(1, ChronoUnit.HOURS).toString());
        if (assigneeId != null) m.put("assigneeId", assigneeId);
        return m;
    }

    JsonNode create(String token, Map<String, Object> body) throws Exception {
        return json(send(post(crm() + "/tasks"), token, body).andExpect(status().isOk()));
    }

    Document raw(String id) {
        return mongo.findOne(new Query(Criteria.where("_id").is(id)), Document.class, "crmTasks");
    }

    String dealId(String name, String phone) throws Exception {
        return captured(contact(name, phone), number).get("deal").get("id").asText();
    }

    @Test
    void createRecordsAssigner() throws Exception {
        enableCrm();
        assignPipelines(agent, salesId());
        String dealId = dealId("Asha", "919900000301");
        JsonNode t = create(managerToken, task("DEAL", dealId, "Call back", agent.getId()));
        assertThat(t.get("assignedBy").get("id").asText()).isEqualTo(manager.getId());
        assertThat(t.get("assignedAt").isNull()).isFalse();
        assertThat(raw(t.get("id").asText()).get("assignmentSeenAt")).isNull();
        JsonNode own = create(managerToken, task("DEAL", dealId, "Mine", null));
        assertThat(own.get("assignedBy").get("id").asText()).isEqualTo(manager.getId());
        assertThat(raw(own.get("id").asText()).get("assignmentSeenAt")).isNotNull();
    }

    @Test
    void reassignRecordsTimelineAndAssigner() throws Exception {
        enableCrm();
        assignPipelines(agent, salesId());
        assignPipelines(agent2, salesId());
        String dealId = dealId("Ravi", "919900000302");
        JsonNode t = create(managerToken, task("DEAL", dealId, "Call back", agent.getId()));
        Map<String, Object> body = task("DEAL", dealId, "Call back", agent2.getId());
        body.put("version", t.get("version").asLong());
        JsonNode saved = json(send(put(crm() + "/tasks/" + t.get("id").asText()), manager2Token, body)
                .andExpect(status().isOk()));
        assertThat(saved.get("assignee").get("id").asText()).isEqualTo(agent2.getId());
        assertThat(saved.get("assignedBy").get("id").asText()).isEqualTo(manager2.getId());
        CrmActivity a = mongo.findOne(new Query(Criteria.where("type").is(ActivityType.TASK_UPDATED)
                .and("payload.action").is("REASSIGNED")), CrmActivity.class);
        assertThat(a).isNotNull();
        assertThat(a.getPayload().get("fromAssigneeId")).isEqualTo(agent.getId());
        assertThat(a.getPayload().get("toAssigneeId")).isEqualTo(agent2.getId());
        assertThat(a.getSummary()).startsWith("Task reassigned to ");
    }

    @Test
    void assigneeMustBeBrandUser() throws Exception {
        enableCrm();
        String dealId = dealId("Lata", "919900000303");
        send(post(crm() + "/tasks"), managerToken, task("DEAL", dealId, "x", staff.getId()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.assigneeId").value("is not a user of this brand"));
    }

    @Test
    void assigneeNeedsCrmView() throws Exception {
        enableCrm();
        User noCrm = userWith("nocrm", "NO_CRM", Set.of("inbox:view"));
        String dealId = dealId("Om", "919900000304");
        send(post(crm() + "/tasks"), managerToken, task("DEAL", dealId, "x", noCrm.getId()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.assigneeId").value("cannot use the CRM"));
    }

    @Test
    void assigneeMustSeeTheRecord() throws Exception {
        enableCrm();
        String secondId = json(send(post(crm() + "/pipelines"), managerToken, Map.of("name", "Second"))
                .andExpect(status().isCreated())).get("id").asText();
        assignPipelines(agent, secondId);
        String dealId = dealId("Ria", "919900000305");
        send(post(crm() + "/tasks"), managerToken, task("DEAL", dealId, "x", agent.getId()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.assigneeId").value("cannot see this record"));
        String companyId = json(send(post(crm() + "/companies"), managerToken, Map.of("name", "Acme Ltd"))
                .andExpect(status().is2xxSuccessful())).get("id").asText();
        create(managerToken, task("COMPANY", companyId, "Visit", agent.getId()));
    }

    @Test
    void unchangedAssigneeNotRechecked() throws Exception {
        enableCrm();
        String secondId = json(send(post(crm() + "/pipelines"), managerToken, Map.of("name", "Second"))
                .andExpect(status().isCreated())).get("id").asText();
        assignPipelines(agent, salesId());
        String dealId = dealId("Tara", "919900000306");
        JsonNode t = create(managerToken, task("DEAL", dealId, "Call back", agent.getId()));
        assignPipelines(agent, secondId);
        Map<String, Object> body = task("DEAL", dealId, "Call back soon", agent.getId());
        body.put("version", t.get("version").asLong());
        send(put(crm() + "/tasks/" + t.get("id").asText()), managerToken, body).andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Call back soon"));
    }
}
