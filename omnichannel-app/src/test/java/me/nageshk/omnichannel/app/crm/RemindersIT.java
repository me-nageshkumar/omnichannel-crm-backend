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
import java.util.Map;
import me.nageshk.omnichannel.crm.job.CrmJobsTicker;
import me.nageshk.omnichannel.crm.job.TaskReminderJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import tools.jackson.databind.JsonNode;

class RemindersIT extends CrmTestBase {

    @Autowired TaskReminderJob job;
    @Autowired ApplicationContext context;

    private String dealId;

    @BeforeEach
    void deal() throws Exception {
        enableCrm();
        dealId = captured(contact("Asha", "919900000301"), number).get("deal").get("id").asText();
    }

    private JsonNode task(String token, String assigneeId, Instant reminderAt) throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("title", "Call " + assigneeId);
        m.put("target", Map.of("type", "DEAL", "id", dealId));
        m.put("dueAt", Instant.now().plus(1, ChronoUnit.DAYS).toString());
        if (reminderAt != null) m.put("reminderAt", reminderAt.toString());
        if (assigneeId != null) m.put("assigneeId", assigneeId);
        return json(send(post(crm() + "/tasks"), token, m).andExpect(status().isOk()));
    }

    private static Instant past() {
        return Instant.now().minus(5, ChronoUnit.MINUTES);
    }

    @Test
    void dueReminderClaimedOnce() throws Exception {
        task(managerToken, manager.getId(), past());
        task(managerToken, manager.getId(), Instant.now().plus(2, ChronoUnit.HOURS));
        assertThat(job.run()).isEqualTo(1);
        assertThat(job.run()).isZero();
    }

    @Test
    void doneTaskNeverReminded() throws Exception {
        JsonNode t = task(managerToken, manager.getId(), past());
        send(post(crm() + "/tasks/" + t.get("id").asText() + "/complete"), managerToken, null).andExpect(status().isOk());
        assertThat(job.run()).isZero();
    }

    @Test
    void noAssigneeNoReminder() throws Exception {
        JsonNode t = task(managerToken, manager.getId(), past());
        mongo.updateFirst(new Query(Criteria.where("_id").is(t.get("id").asText())), new Update().unset("assigneeId"),
                "crmTasks");
        assertThat(job.run()).isZero();
    }

    @Test
    void editingReminderReArms() throws Exception {
        JsonNode t = task(managerToken, manager.getId(), past());
        assertThat(job.run()).isEqualTo(1);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", "Call again");
        body.put("dueAt", Instant.now().plus(1, ChronoUnit.DAYS).toString());
        body.put("reminderAt", Instant.now().minus(1, ChronoUnit.MINUTES).toString());
        body.put("version", t.get("version").asLong() + 1); // the claim does not bump the version
        send(put(crm() + "/tasks/" + t.get("id").asText()), managerToken, body).andExpect(status().isConflict());
        body.put("version", t.get("version").asLong());
        send(put(crm() + "/tasks/" + t.get("id").asText()), managerToken, body).andExpect(status().isOk());
        assertThat(job.run()).isEqualTo(1);
    }

    @Test
    void remindersListsOnlyOwnUnseen() throws Exception {
        task(managerToken, manager.getId(), past());
        task(managerToken, agent.getId(), past());
        assignPipelines(agent, salesId());
        job.run();
        send(get(crm() + "/reminders"), managerToken, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].title").value("Call " + manager.getId()))
                .andExpect(jsonPath("$[0].target.type").value("DEAL"))
                .andExpect(jsonPath("$[0].target.name").exists());
        send(get(crm() + "/reminders"), agentToken, null).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].title").value("Call " + agent.getId()));
    }

    @Test
    void reminderOnHiddenDealNotListed() throws Exception {
        task(managerToken, agent.getId(), past());
        job.run();
        // the agent has no pipeline, so the deal (and its task) is out of sight
        send(get(crm() + "/reminders"), agentToken, null).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void seenRemovesFromList() throws Exception {
        JsonNode t = task(managerToken, manager.getId(), past());
        job.run();
        send(post(crm() + "/reminders/" + t.get("id").asText() + "/seen"), managerToken, null)
                .andExpect(status().isNoContent());
        send(get(crm() + "/reminders"), managerToken, null).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void othersTaskSeenIs404() throws Exception {
        JsonNode t = task(managerToken, manager.getId(), past());
        assignPipelines(agent, salesId());
        job.run();
        send(post(crm() + "/reminders/" + t.get("id").asText() + "/seen"), agentToken, null)
                .andExpect(status().isNotFound());
    }

    @Test
    void jobDisabledByProperty() {
        // the test profile sets omnichannel.crm.jobs.enabled=false, so no scheduled ticker exists
        assertThat(context.getBeanNamesForType(CrmJobsTicker.class)).isEmpty();
    }
}
