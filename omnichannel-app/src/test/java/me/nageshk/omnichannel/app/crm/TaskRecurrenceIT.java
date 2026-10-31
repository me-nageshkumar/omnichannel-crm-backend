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
import me.nageshk.omnichannel.crm.task.Recurrence;
import me.nageshk.omnichannel.crm.task.RecurrenceFrequency;
import me.nageshk.omnichannel.crm.task.TaskRecurrenceSchedule;
import me.nageshk.omnichannel.db.user.User;
import org.bson.Document;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Repeating tasks (spec E C11–C14): the rule on save, the next occurrence on complete, skip. */
class TaskRecurrenceIT extends CrmTestBase {

    String dealId;

    @BeforeEach
    void deal() throws Exception {
        enableCrm();
        dealId = captured(contact("Asha", "919900000501"), number).get("deal").get("id").asText();
    }

    Map<String, Object> body(String title, Instant dueAt, Map<String, Object> recurrence) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("title", title);
        m.put("target", Map.of("type", "DEAL", "id", dealId));
        if (dueAt != null) m.put("dueAt", dueAt.toString());
        if (recurrence != null) m.put("recurrence", recurrence);
        return m;
    }

    static Map<String, Object> weekly(String... weekdays) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("frequency", "WEEKLY");
        r.put("interval", 1);
        r.put("weekdays", List.of(weekdays));
        r.put("timeZone", "Asia/Kolkata");
        return r;
    }

    static Map<String, Object> daily(int interval) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("frequency", "DAILY");
        r.put("interval", interval);
        r.put("timeZone", "UTC");
        return r;
    }

    JsonNode create(String token, Map<String, Object> body) throws Exception {
        return json(send(post(crm() + "/tasks"), token, body).andExpect(status().isOk()));
    }

    JsonNode update(String token, JsonNode task, Map<String, Object> body) throws Exception {
        body.put("version", task.get("version").asLong());
        return json(send(put(crm() + "/tasks/" + task.get("id").asText()), token, body).andExpect(status().isOk()));
    }

    @Test
    void createStartsASeries() throws Exception {
        JsonNode t = create(managerToken, body("Stock check", Instant.now().plus(1, ChronoUnit.DAYS),
                weekly("THURSDAY", "MONDAY", "MONDAY")));
        assertThat(t.get("recurrence").get("weekdays")).extracting(JsonNode::asText).containsExactly("MONDAY", "THURSDAY");
        assertThat(t.get("recurrence").get("interval").asInt()).isEqualTo(1);
        assertThat(t.get("seriesId").asText()).isEqualTo(t.get("id").asText());
        assertThat(t.get("occurrence").asInt()).isEqualTo(1);
        assertThat(t.get("recurringFromTaskId").isNull()).isTrue();
        assertThat(t.get("nextOccurrenceId").isNull()).isTrue();
    }

    @Test
    void recurrenceNeedsDueDate() throws Exception {
        send(post(crm() + "/tasks"), managerToken, body("Stock check", null, daily(1))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.recurrence").value("needs a due date"));
    }

    @Test
    void badRuleReportsEveryField() throws Exception {
        Map<String, Object> r = weekly();
        r.put("interval", 0);
        send(post(crm() + "/tasks"), managerToken, body("Stock check", Instant.now().plus(1, ChronoUnit.DAYS), r))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields['recurrence.interval']").value("must be 1 to 99"))
                .andExpect(jsonPath("$.fields['recurrence.weekdays']").value("pick at least one weekday"));
    }

    @Test
    void badEnumValuesAreFieldErrors() throws Exception {
        Map<String, Object> r = weekly("MONDAY", "FUNDAY");
        send(post(crm() + "/tasks"), managerToken, body("Stock check", Instant.now().plus(1, ChronoUnit.DAYS), r))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields['recurrence.weekdays']").value("must be days of the week, MONDAY to SUNDAY"));
        r = daily(1);
        r.put("frequency", "FORTNIGHTLY");
        send(post(crm() + "/tasks"), managerToken, body("Stock check", Instant.now().plus(1, ChronoUnit.DAYS), r))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields['recurrence.frequency']").value("must be DAILY, WEEKLY or MONTHLY"));
    }

    @Test
    void removingRecurrenceKeepsSeriesId() throws Exception {
        JsonNode t = create(managerToken, body("Stock check", Instant.now().plus(1, ChronoUnit.DAYS), daily(2)));
        JsonNode saved = update(managerToken, t, body("Stock check", Instant.now().plus(1, ChronoUnit.DAYS), null));
        assertThat(saved.get("recurrence").isNull()).isTrue();
        assertThat(saved.get("seriesId").asText()).isEqualTo(t.get("id").asText());
        assertThat(saved.get("occurrence").asInt()).isEqualTo(1);
    }

    @Test
    void addingRecurrenceLaterStartsSeriesAtThisTask() throws Exception {
        JsonNode t = create(managerToken, body("Stock check", Instant.now().plus(1, ChronoUnit.DAYS), null));
        assertThat(t.get("seriesId").isNull()).isTrue();
        JsonNode saved = update(managerToken, t, body("Stock check", Instant.now().plus(1, ChronoUnit.DAYS), daily(3)));
        assertThat(saved.get("recurrence").get("interval").asInt()).isEqualTo(3);
        assertThat(saved.get("seriesId").asText()).isEqualTo(t.get("id").asText());
        assertThat(saved.get("occurrence").asInt()).isEqualTo(1);
    }

    // --- the next occurrence (C13), skip (C14) ------------------------------------------------------------

    Document rawTask(String id) {
        return mongo.findOne(new Query(Criteria.where("_id").is(id)), Document.class, "crmTasks");
    }

    List<Document> occurrencesAfter(String id) {
        return mongo.find(new Query(Criteria.where("recurringFromTaskId").is(id)), Document.class, "crmTasks");
    }

    JsonNode complete(String token, JsonNode task) throws Exception {
        return json(send(post(crm() + "/tasks/" + task.get("id").asText() + "/complete"), token, null)
                .andExpect(status().isOk()));
    }

    @Test
    void completeSchedulesNextOccurrence() throws Exception {
        assignPipelines(agent, salesId());
        Instant due = Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        Map<String, Object> b = body("Stock check", due, weekly("MONDAY", "THURSDAY"));
        b.put("reminderAt", due.minus(1, ChronoUnit.HOURS).toString());
        b.put("assigneeId", agent.getId());
        b.put("checklist", List.of(Map.of("text", "Count"), Map.of("text", "Report")));
        JsonNode t = create(managerToken, b);
        send(post(crm() + "/tasks/" + t.get("id").asText() + "/checklist/" + t.get("checklist").get(0).get("id").asText()),
                agentToken, Map.of("done", true)).andExpect(status().isOk());
        JsonNode done = complete(agentToken, t);
        assertThat(done.get("status").asText()).isEqualTo("DONE");
        String nextId = done.get("nextOccurrenceId").asText();
        Document next = rawTask(nextId);
        assertThat(next).isNotNull();
        assertThat(next.getInteger("occurrence")).isEqualTo(2);
        assertThat(next.getString("seriesId")).isEqualTo(t.get("id").asText());
        assertThat(next.getString("recurringFromTaskId")).isEqualTo(t.get("id").asText());
        assertThat(next.getString("assigneeId")).isEqualTo(agent.getId());
        assertThat(next.getString("assignedBy")).isEqualTo(manager.getId());
        assertThat(next.get("assignmentSeenAt")).isNotNull();
        Recurrence rule = new Recurrence(RecurrenceFrequency.WEEKLY, 1,
                List.of(java.time.DayOfWeek.MONDAY, java.time.DayOfWeek.THURSDAY), null, "Asia/Kolkata", null, null);
        Instant expectedDue = TaskRecurrenceSchedule.nextDue(rule, due, 1, Instant.now()).orElseThrow();
        assertThat(next.getDate("dueAt").toInstant()).isEqualTo(expectedDue);
        assertThat(next.getDate("reminderAt").toInstant()).isEqualTo(expectedDue.minus(1, ChronoUnit.HOURS));
        List<Document> steps = next.getList("checklist", Document.class);
        assertThat(steps).extracting(d -> d.getString("text")).containsExactly("Count", "Report");
        assertThat(steps).allSatisfy(d -> assertThat(d.getBoolean("done")).isFalse());
        // embedded ids are stored as _id (Spring's mapping); the wire name is id
        assertThat(String.valueOf(steps.get(0).get("_id"))).hasSize(24)
                .isNotEqualTo(t.get("checklist").get(0).get("id").asText());
        CrmActivity a = mongo.findOne(new Query(Criteria.where("type").is(ActivityType.TASK_CREATED)
                .and("payload.recurringFromTaskId").is(t.get("id").asText())), CrmActivity.class);
        assertThat(a).isNotNull();
        assertThat(a.getSummary()).isEqualTo("Next task scheduled: Stock check");
        Document deal = mongo.findOne(new Query(Criteria.where("_id").is(dealId)), Document.class, "crmDeals");
        assertThat(deal.getDate("nextTaskDueAt").toInstant()).isEqualTo(expectedDue);
    }

    @Test
    void pastReminderOffsetIsDropped() throws Exception {
        Instant due = Instant.now().minus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        Map<String, Object> b = body("Daily call", due, daily(1));
        b.put("reminderAt", due.minus(3, ChronoUnit.DAYS).toString());
        JsonNode t = create(managerToken, b);
        JsonNode done = complete(managerToken, t);
        Document next = rawTask(done.get("nextOccurrenceId").asText());
        assertThat(next.getDate("dueAt").toInstant()).isAfter(Instant.now());
        assertThat(next.get("reminderAt")).isNull();
    }

    @Test
    void reopenAndCompleteCreatesNothing() throws Exception {
        JsonNode t = create(managerToken, body("Daily call", Instant.now().plus(1, ChronoUnit.DAYS), daily(1)));
        JsonNode done = complete(managerToken, t);
        send(post(crm() + "/tasks/" + t.get("id").asText() + "/reopen"), managerToken, null).andExpect(status().isOk());
        JsonNode again = complete(managerToken, t);
        assertThat(again.get("nextOccurrenceId").asText()).isEqualTo(done.get("nextOccurrenceId").asText());
        assertThat(occurrencesAfter(t.get("id").asText())).hasSize(1);
    }

    @Test
    void endsAfterStopsTheSeries() throws Exception {
        Map<String, Object> rule = daily(1);
        rule.put("endsAfter", 2);
        JsonNode t = create(managerToken, body("Daily call", Instant.now().plus(1, ChronoUnit.DAYS), rule));
        JsonNode done = complete(managerToken, t);
        String secondId = done.get("nextOccurrenceId").asText();
        JsonNode second = json(send(get(crm() + "/tasks?targetType=DEAL&targetId=" + dealId), managerToken, null))
                .get("items").get(0);
        assertThat(second.get("id").asText()).isEqualTo(secondId);
        JsonNode lastDone = complete(managerToken, second);
        assertThat(lastDone.get("nextOccurrenceId").isNull()).isTrue();
        assertThat(occurrencesAfter(secondId)).isEmpty();
    }

    @Test
    void skipCancelsAndSchedules() throws Exception {
        JsonNode t = create(managerToken, body("Daily call", Instant.now().plus(1, ChronoUnit.DAYS), daily(1)));
        JsonNode r = json(send(post(crm() + "/tasks/" + t.get("id").asText() + "/skip"), managerToken, null)
                .andExpect(status().isOk()));
        assertThat(r.get("skipped").get("status").asText()).isEqualTo("CANCELLED");
        assertThat(r.get("next").get("occurrence").asInt()).isEqualTo(2);
        assertThat(r.get("skipped").get("nextOccurrenceId").asText()).isEqualTo(r.get("next").get("id").asText());
        CrmActivity a = mongo.findOne(new Query(Criteria.where("type").is(ActivityType.TASK_UPDATED)
                .and("payload.action").is("SKIPPED")), CrmActivity.class);
        assertThat(a).isNotNull();
        assertThat(a.getSummary()).isEqualTo("Task skipped: Daily call");
    }

    @Test
    void skipOneOffIs400() throws Exception {
        JsonNode t = create(managerToken, body("Once", Instant.now().plus(1, ChronoUnit.DAYS), null));
        send(post(crm() + "/tasks/" + t.get("id").asText() + "/skip"), managerToken, null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("NOT_RECURRING"));
    }

    @Test
    void skipDoneIs400() throws Exception {
        JsonNode t = create(managerToken, body("Daily call", Instant.now().plus(1, ChronoUnit.DAYS), daily(1)));
        complete(managerToken, t);
        send(post(crm() + "/tasks/" + t.get("id").asText() + "/skip"), managerToken, null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TASK_NOT_OPEN"));
    }

    @Test
    void cancelledIsListedOnTheRecordButNotInBuckets() throws Exception {
        Map<String, Object> rule = daily(1);
        rule.put("endsAfter", 2);
        JsonNode t = create(managerToken, body("Daily call", Instant.now().plus(1, ChronoUnit.DAYS), rule));
        JsonNode r = json(send(post(crm() + "/tasks/" + t.get("id").asText() + "/skip"), managerToken, null));
        send(post(crm() + "/tasks/" + r.get("next").get("id").asText() + "/skip"), managerToken, null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.next").isEmpty());
        JsonNode list = json(send(get(crm() + "/tasks?targetType=DEAL&targetId=" + dealId), managerToken, null));
        assertThat(list.get("items")).hasSize(2);
        assertThat(list.get("items").get(0).get("status").asText()).isEqualTo("CANCELLED");
        send(get(crm() + "/tasks/mine?bucket=upcoming"), managerToken, null).andExpect(jsonPath("$.length()").value(0));
        send(get(crm() + "/tasks/mine/counts"), managerToken, null).andExpect(jsonPath("$.upcoming").value(0));
        send(get(crm() + "/reminders"), managerToken, null).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void completeOrReopenOnSkippedIs400() throws Exception {
        JsonNode t = create(managerToken, body("Daily call", Instant.now().plus(1, ChronoUnit.DAYS), daily(1)));
        send(post(crm() + "/tasks/" + t.get("id").asText() + "/skip"), managerToken, null).andExpect(status().isOk());
        send(post(crm() + "/tasks/" + t.get("id").asText() + "/complete"), managerToken, null)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("TASK_NOT_OPEN"));
        send(post(crm() + "/tasks/" + t.get("id").asText() + "/reopen"), managerToken, null)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("TASK_NOT_OPEN"));
        assertThat(occurrencesAfter(t.get("id").asText())).hasSize(1);
    }

    @Test
    void secondCompletionIsIdempotent() throws Exception {
        JsonNode t = create(managerToken, body("Daily call", Instant.now().plus(1, ChronoUnit.DAYS), daily(1)));
        JsonNode first = complete(managerToken, t);
        JsonNode second = complete(managerToken, t);
        assertThat(second.get("nextOccurrenceId").asText()).isEqualTo(first.get("nextOccurrenceId").asText());
        assertThat(occurrencesAfter(t.get("id").asText())).hasSize(1);
    }

    @Test
    void agentWithoutEditCanSkipOwnTask() throws Exception {
        User viewer = brandUserWith("viewer", "CRM_VIEWER", Set.of("crm:view"));
        User viewer2 = brandUserWith("viewer2", "CRM_VIEWER_2", Set.of("crm:view"));
        assignPipelines(viewer, salesId());
        assignPipelines(viewer2, salesId());
        Map<String, Object> b = body("Daily call", Instant.now().plus(1, ChronoUnit.DAYS), daily(1));
        b.put("assigneeId", viewer.getId());
        JsonNode t = create(managerToken, b);
        send(post(crm() + "/tasks/" + t.get("id").asText() + "/skip"), testUsers.accessToken(viewer2), null)
                .andExpect(status().isForbidden());
        send(post(crm() + "/tasks/" + t.get("id").asText() + "/skip"), testUsers.accessToken(viewer), null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.next.assignee.id").value(viewer.getId()));
    }
}
