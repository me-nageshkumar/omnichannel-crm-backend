package me.nageshk.omnichannel.app.crm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import me.nageshk.omnichannel.crm.activity.ActivityType;
import me.nageshk.omnichannel.crm.activity.CrmActivity;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import me.nageshk.omnichannel.db.contact.Contact;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import tools.jackson.databind.JsonNode;

class TasksApiIT extends CrmTestBase {

    /** A UTC offset where it is around noon now, so "today" never straddles midnight in a test. */
    private static String noonZone() {
        int hour = Instant.now().atZone(ZoneOffset.UTC).getHour();
        int offset = 12 - hour;
        if (offset > 14) offset -= 24;
        if (offset < -12) offset += 24;
        return ZoneOffset.ofHours(offset).getId();
    }

    private Map<String, Object> task(String type, String id, String title, Instant dueAt) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("title", title);
        m.put("target", Map.of("type", type, "id", id));
        if (dueAt != null) m.put("dueAt", dueAt.toString());
        return m;
    }

    private JsonNode create(String token, Map<String, Object> body) throws Exception {
        return json(send(post(crm() + "/tasks"), token, body).andExpect(status().isOk()));
    }

    private Document raw(String collection, String id) {
        return mongo.findOne(new Query(Criteria.where("_id").is(id)), Document.class, collection);
    }

    @Test
    void createOnDealSetsNextTaskDueAtAndTimeline() throws Exception {
        enableCrm();
        JsonNode deal = captured(contact("Asha", "919900000201"), number).get("deal");
        String dealId = deal.get("id").asText();
        Instant later = Instant.now().plus(3, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        Instant sooner = Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        JsonNode t = create(managerToken, task("DEAL", dealId, "Call back", later));
        assertThat(t.get("status").asText()).isEqualTo("OPEN");
        assertThat(t.get("assignee").get("id").asText()).isEqualTo(manager.getId());
        assertThat(t.get("target").get("name").asText()).isEqualTo(deal.get("title").asText());
        create(managerToken, task("DEAL", dealId, "Send quote", sooner));
        Document d = raw("crmDeals", dealId);
        assertThat(((java.util.Date) d.get("nextTaskDueAt")).toInstant()).isEqualTo(sooner);
        assertThat(d.get("version")).isEqualTo(deal.get("version").asLong());
        assertThat(mongo.count(new Query(Criteria.where("type").is(ActivityType.TASK_CREATED)
                .and("target.id").is(dealId)), CrmActivity.class)).isEqualTo(2);
        send(get(crm() + "/deals/" + dealId), managerToken, null).andExpect(jsonPath("$.nextTaskDueAt").exists());
    }

    @Test
    void completeClearsNextTaskDueAt() throws Exception {
        enableCrm();
        String dealId = captured(contact("Ravi", "919900000202"), number).get("deal").get("id").asText();
        JsonNode t = create(managerToken, task("DEAL", dealId, "Call", Instant.now().plus(1, ChronoUnit.DAYS)));
        JsonNode done = json(send(post(crm() + "/tasks/" + t.get("id").asText() + "/complete"), managerToken, null)
                .andExpect(status().isOk()));
        assertThat(done.get("status").asText()).isEqualTo("DONE");
        assertThat(done.get("completedBy").get("id").asText()).isEqualTo(manager.getId());
        assertThat(raw("crmDeals", dealId).get("nextTaskDueAt")).isNull();
        assertThat(mongo.count(new Query(Criteria.where("type").is(ActivityType.TASK_COMPLETED)), CrmActivity.class))
                .isEqualTo(1);
    }

    @Test
    void reopenRecordsAction() throws Exception {
        enableCrm();
        String contactId = captured(contact("Meena", "919900000203"), number).get("contact").get("id").asText();
        JsonNode t = create(managerToken, task("CONTACT", contactId, "Follow up", Instant.now().plus(2, ChronoUnit.DAYS)));
        send(post(crm() + "/tasks/" + t.get("id").asText() + "/complete"), managerToken, null).andExpect(status().isOk());
        send(post(crm() + "/tasks/" + t.get("id").asText() + "/reopen"), managerToken, null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("OPEN"));
        CrmActivity a = mongo.findOne(new Query(Criteria.where("type").is(ActivityType.TASK_UPDATED)), CrmActivity.class);
        assertThat(a.getPayload()).containsEntry("action", "REOPENED");
        assertThat(raw("crmContacts", contactId).get("nextTaskDueAt")).isNotNull();
    }

    @Test
    void updateReplacesFieldsAndChecksVersion() throws Exception {
        enableCrm();
        String dealId = captured(contact("Kiran", "919900000204"), number).get("deal").get("id").asText();
        JsonNode t = create(managerToken, task("DEAL", dealId, "Call", null));
        Map<String, Object> body = task("DEAL", dealId, "Call again", Instant.now().plus(1, ChronoUnit.DAYS));
        body.put("priority", "HIGH");
        body.put("version", t.get("version").asLong());
        send(put(crm() + "/tasks/" + t.get("id").asText()), managerToken, body).andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Call again")).andExpect(jsonPath("$.priority").value("HIGH"));
        send(put(crm() + "/tasks/" + t.get("id").asText()), managerToken, body).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
    }

    @Test
    void restrictedAgentCannotSeeTaskOnInaccessibleDeal() throws Exception {
        enableCrm();
        Map<String, Object> other = Map.of("name", "Second");
        String secondId = json(send(post(crm() + "/pipelines"), managerToken, other).andExpect(status().isCreated()))
                .get("id").asText();
        String dealId = captured(contact("Lata", "919900000205"), number).get("deal").get("id").asText();
        JsonNode t = create(managerToken, task("DEAL", dealId, "Hidden", Instant.now().minus(1, ChronoUnit.HOURS)));
        Map<String, Object> asAgent = task("DEAL", dealId, "Mine", Instant.now().minus(1, ChronoUnit.HOURS));
        asAgent.put("assigneeId", agent.getId());
        create(managerToken, asAgent);
        assignPipelines(agent, secondId);
        send(get(crm() + "/tasks?targetType=DEAL&targetId=" + dealId), agentToken, null)
                .andExpect(status().isNotFound());
        send(put(crm() + "/tasks/" + t.get("id").asText()), agentToken,
                Map.of("title", "x", "version", t.get("version").asLong())).andExpect(status().isNotFound());
        send(get(crm() + "/tasks/mine?bucket=overdue"), agentToken, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        assignPipelines(agent, salesId());
        send(get(crm() + "/tasks/mine?bucket=overdue"), agentToken, null)
                .andExpect(jsonPath("$[0].title").value("Mine"));
    }

    @Test
    void companyTasksVisibleToAll() throws Exception {
        enableCrm();
        String companyId = json(send(post(crm() + "/companies"), managerToken, Map.of("name", "Acme Co"))
                .andExpect(status().isOk())).get("id").asText();
        Map<String, Object> body = task("COMPANY", companyId, "Visit", Instant.now().minus(1, ChronoUnit.HOURS));
        body.put("assigneeId", agent.getId());
        create(managerToken, body);
        send(get(crm() + "/tasks?targetType=COMPANY&targetId=" + companyId), agentToken, null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].target.name").value("Acme Co"));
        send(get(crm() + "/tasks/mine?bucket=overdue"), agentToken, null)
                .andExpect(jsonPath("$[0].title").value("Visit"));
    }

    @Test
    void mineOtherAssigneeNeedsViewAll() throws Exception {
        enableCrm();
        send(get(crm() + "/tasks/mine?bucket=today&assigneeId=" + manager.getId()), agentToken, null)
                .andExpect(status().isForbidden());
        send(get(crm() + "/tasks/mine?bucket=today&assigneeId=" + agent.getId()), managerToken, null)
                .andExpect(status().isOk());
        send(get(crm() + "/tasks/mine?bucket=today&tz=Mars/Base"), managerToken, null)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.tz").exists());
    }

    @Test
    void taskFollowsDealToAnotherPipeline() throws Exception {
        enableCrm();
        String second = json(send(post(crm() + "/pipelines"), managerToken, Map.of("name", "Second"))
                .andExpect(status().isCreated())).get("id").asText();
        JsonNode deal = captured(contact("Lata", "919900000207"), number).get("deal");
        String dealId = deal.get("id").asText();
        Map<String, Object> mine = task("DEAL", dealId, "Mine", Instant.now().minus(1, ChronoUnit.HOURS));
        mine.put("assigneeId", agent.getId());
        create(managerToken, mine);
        assignPipelines(agent, salesId());
        send(get(crm() + "/tasks/mine?bucket=overdue"), agentToken, null).andExpect(jsonPath("$.length()").value(1));
        send(put(crm() + "/deals/" + dealId), managerToken,
                Map.of("version", deal.get("version").asLong(), "pipelineId", second)).andExpect(status().isOk());
        // the deal is now in a pipeline the agent cannot see: so is its task
        send(get(crm() + "/tasks/mine?bucket=overdue"), agentToken, null).andExpect(jsonPath("$.length()").value(0));
        assignPipelines(agent, second);
        send(get(crm() + "/tasks/mine?bucket=overdue"), agentToken, null).andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void countsMatchBuckets() throws Exception {
        enableCrm();
        String dealId = captured(contact("Nila", "919900000206"), number).get("deal").get("id").asText();
        String tz = noonZone();
        create(managerToken, task("DEAL", dealId, "Late", Instant.now().minus(2, ChronoUnit.HOURS)));
        create(managerToken, task("DEAL", dealId, "Soon", Instant.now().plus(1, ChronoUnit.MINUTES)));
        create(managerToken, task("DEAL", dealId, "Later", Instant.now().plus(3, ChronoUnit.DAYS)));
        create(managerToken, task("DEAL", dealId, "Someday", null));
        send(get(crm() + "/tasks/mine/counts").param("tz", tz), managerToken, null)
                .andExpect(jsonPath("$.overdue").value(1)).andExpect(jsonPath("$.today").value(1))
                .andExpect(jsonPath("$.upcoming").value(2));
        JsonNode upcoming = json(send(get(crm() + "/tasks/mine").param("bucket", "upcoming").param("tz", tz),
                managerToken, null));
        assertThat(upcoming.get(0).get("title").asText()).isEqualTo("Later");
        assertThat(upcoming.get(1).get("title").asText()).isEqualTo("Someday");
        JsonNode overdue = json(send(get(crm() + "/tasks/mine?bucket=overdue"), managerToken, null));
        assertThat(overdue.get(0).get("overdue").asBoolean()).isTrue();
    }

    @Test
    void unknownTaskTypeRejected() throws Exception {
        enableCrm();
        String dealId = captured(contact("Om", "919900000207"), number).get("deal").get("id").asText();
        Map<String, Object> body = task("DEAL", dealId, "Call", null);
        body.put("type", "carrier_pigeon");
        send(post(crm() + "/tasks"), managerToken, body).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.type").exists());
        body.put("type", "call");
        send(post(crm() + "/tasks"), managerToken, body).andExpect(status().isOk());
    }

    @Test
    void reminderAfterDueRejected() throws Exception {
        enableCrm();
        String dealId = captured(contact("Pia", "919900000208"), number).get("deal").get("id").asText();
        Instant due = Instant.now().plus(1, ChronoUnit.DAYS);
        Map<String, Object> body = task("DEAL", dealId, "Call", due);
        body.put("reminderAt", due.plus(1, ChronoUnit.HOURS).toString());
        send(post(crm() + "/tasks"), managerToken, body).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.reminderAt").exists());
    }

    @Test
    void agentWithoutEditCannotCreate() throws Exception {
        enableCrm();
        String dealId = captured(contact("Ria", "919900000209"), number).get("deal").get("id").asText();
        send(post(crm() + "/tasks"), staffToken, task("DEAL", dealId, "x", null)).andExpect(status().is4xxClientError());
    }

    @Test
    void deletingDealRemovesItsTasks() throws Exception {
        enableCrm();
        String dealId = captured(contact("Sam", "919900000210"), number).get("deal").get("id").asText();
        create(managerToken, task("DEAL", dealId, "Call", null));
        send(delete(crm() + "/deals/" + dealId), managerToken, null).andExpect(status().isNoContent());
        assertThat(mongo.count(new Query(Criteria.where("target.id").is(dealId)), "crmTasks")).isZero();
    }

    @Test
    void deletingCompanyRemovesItsTasks() throws Exception {
        enableCrm();
        String companyId = json(send(post(crm() + "/companies"), managerToken, Map.of("name", "Gone Co"))
                .andExpect(status().isOk())).get("id").asText();
        create(managerToken, task("COMPANY", companyId, "Visit", null));
        send(delete(crm() + "/companies/" + companyId), managerToken, null).andExpect(status().isNoContent());
        assertThat(mongo.count(new Query(Criteria.where("target.id").is(companyId)), "crmTasks")).isZero();
    }

    @Test
    void deleteTaskRecordsAction() throws Exception {
        enableCrm();
        String dealId = captured(contact("Tara", "919900000211"), number).get("deal").get("id").asText();
        JsonNode t = create(managerToken, task("DEAL", dealId, "Call", Instant.now().plus(1, ChronoUnit.DAYS)));
        send(delete(crm() + "/tasks/" + t.get("id").asText()), managerToken, null).andExpect(status().isNoContent());
        assertThat(raw("crmDeals", dealId).get("nextTaskDueAt")).isNull();
        CrmActivity a = mongo.findOne(new Query(Criteria.where("type").is(ActivityType.TASK_UPDATED)), CrmActivity.class);
        assertThat(a.getPayload()).containsEntry("action", "DELETED");
    }

    @Test
    void inboxSummaryShowsNextTask() throws Exception {
        enableCrm();
        Contact c = contact("Uma", "919900000212");
        WaWebChat chat = chat(c, number, null, null);
        JsonNode captured = json(send(post(crm() + "/contacts/capture"), managerToken, Map.of("chatId", chat.getId()))
                .andExpect(status().isOk()));
        String dealId = captured.get("deal").get("id").asText();
        create(managerToken, task("DEAL", dealId, "Later", Instant.now().plus(3, ChronoUnit.DAYS)));
        create(managerToken, task("CONTACT", captured.get("contact").get("id").asText(), "Sooner",
                Instant.now().plus(1, ChronoUnit.DAYS)));
        send(get(crm() + "/contacts/by-chat/" + chat.getId() + "/summary"), managerToken, null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.nextTask.title").value("Sooner"));
    }

    @Test
    void movingChannelRePointsTasks() throws Exception {
        enableCrm();
        JsonNode captured = captured(contact("Vani", "919900000213"), number);
        String contactId = captured.get("contact").get("id").asText();
        String dealId = captured.get("deal").get("id").asText();
        create(managerToken, task("CONTACT", contactId, "On contact", null));
        create(managerToken, task("DEAL", dealId, "On deal", null));
        Map<String, Object> body = new HashMap<>();
        body.put("toPhoneNumberId", number2.getId());
        body.put("version", captured.get("contact").get("version").asLong());
        send(post(crm() + "/contacts/" + contactId + "/move-channel"), managerToken, body).andExpect(status().isOk());
        List<Document> tasks = mongo.find(new Query(), Document.class, "crmTasks");
        assertThat(tasks).hasSize(2).allSatisfy(t -> assertThat(t.getString("phoneNumberId")).isEqualTo(number2.getId()));
    }
}
