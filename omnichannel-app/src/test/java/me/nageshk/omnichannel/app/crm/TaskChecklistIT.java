package me.nageshk.omnichannel.app.crm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;
import me.nageshk.omnichannel.crm.activity.CrmActivity;
import me.nageshk.omnichannel.db.user.User;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import tools.jackson.databind.JsonNode;

/** Checklists (spec E C7–C9): save merges by id, ticking is atomic and bumps the version, no timeline entry. */
class TaskChecklistIT extends CrmTestBase {

    private String dealId;

    @BeforeEach
    void deal() throws Exception {
        enableCrm();
        dealId = captured(contact("Asha", "919900000401"), number).get("deal").get("id").asText();
    }

    private Map<String, Object> body(String title, List<Map<String, String>> checklist) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("title", title);
        m.put("target", Map.of("type", "DEAL", "id", dealId));
        m.put("dueAt", Instant.now().plus(1, ChronoUnit.DAYS).toString());
        if (checklist != null) m.put("checklist", checklist);
        return m;
    }

    private static List<Map<String, String>> steps(String... texts) {
        List<Map<String, String>> out = new ArrayList<>();
        for (String t : texts) out.add(Map.of("text", t));
        return out;
    }

    private JsonNode create(String token, Map<String, Object> body) throws Exception {
        return json(send(post(crm() + "/tasks"), token, body).andExpect(status().isOk()));
    }

    private JsonNode tick(String token, JsonNode task, int index, boolean done) throws Exception {
        return json(send(post(crm() + "/tasks/" + task.get("id").asText() + "/checklist/"
                + task.get("checklist").get(index).get("id").asText()), token, Map.of("done", done))
                .andExpect(status().isOk()));
    }

    @Test
    void saveMergesByIdAndIgnoresDoneFlags() throws Exception {
        JsonNode t = create(managerToken, body("Onboard", steps("Send price list", "Collect GST")));
        assertThat(t.get("checklist")).hasSize(2);
        JsonNode ticked = tick(managerToken, t, 0, true);
        String tickedId = ticked.get("checklist").get(0).get("id").asText();
        Map<String, Object> update = body("Onboard", List.of(
                Map.of("id", tickedId, "text", "Send price list PDF", "done", "false"), Map.of("text", "Book demo")));
        update.put("version", ticked.get("version").asLong());
        JsonNode saved = json(send(put(crm() + "/tasks/" + t.get("id").asText()), managerToken, update)
                .andExpect(status().isOk()));
        assertThat(saved.get("checklist")).hasSize(2);
        assertThat(saved.get("checklist").get(0).get("id").asText()).isEqualTo(tickedId);
        assertThat(saved.get("checklist").get(0).get("text").asText()).isEqualTo("Send price list PDF");
        assertThat(saved.get("checklist").get(0).get("done").asBoolean()).isTrue();
        assertThat(saved.get("checklist").get(0).get("doneBy").get("id").asText()).isEqualTo(manager.getId());
        assertThat(saved.get("checklist").get(1).get("text").asText()).isEqualTo("Book demo");
        assertThat(saved.get("checklist").get(1).get("done").asBoolean()).isFalse();
        assertThat(saved.get("checklist").get(1).get("id").asText()).hasSize(24);
    }

    @Test
    void tickBumpsVersionSoStaleSaveConflicts() throws Exception {
        JsonNode t = create(managerToken, body("Onboard", steps("Send price list")));
        long v = t.get("version").asLong();
        JsonNode ticked = tick(managerToken, t, 0, true);
        assertThat(ticked.get("version").asLong()).isEqualTo(v + 1);
        assertThat(ticked.get("checklist").get(0).get("done").asBoolean()).isTrue();
        assertThat(ticked.get("checklist").get(0).get("doneAt").isNull()).isFalse();
        Map<String, Object> stale = body("Onboard", steps("Send price list"));
        stale.put("version", v);
        send(put(crm() + "/tasks/" + t.get("id").asText()), managerToken, stale).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        JsonNode unticked = tick(managerToken, ticked, 0, false);
        assertThat(unticked.get("checklist").get(0).get("done").asBoolean()).isFalse();
        assertThat(unticked.get("checklist").get(0).get("doneBy").isNull()).isTrue();
    }

    @Test
    void tickOnDoneTaskIs400() throws Exception {
        JsonNode t = create(managerToken, body("Onboard", steps("Send price list")));
        send(post(crm() + "/tasks/" + t.get("id").asText() + "/complete"), managerToken, null).andExpect(status().isOk());
        send(post(crm() + "/tasks/" + t.get("id").asText() + "/checklist/" + t.get("checklist").get(0).get("id").asText()),
                managerToken, Map.of("done", true)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TASK_NOT_OPEN"));
    }

    @Test
    void unknownItemIs404() throws Exception {
        JsonNode t = create(managerToken, body("Onboard", steps("Send price list")));
        send(post(crm() + "/tasks/" + t.get("id").asText() + "/checklist/6723f0a1b2c3d4e5f6a7b8c9"), managerToken,
                Map.of("done", true)).andExpect(status().isNotFound());
        send(post(crm() + "/tasks/" + t.get("id").asText() + "/checklist/" + t.get("checklist").get(0).get("id").asText()),
                managerToken, Map.of()).andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.done").exists());
    }

    @Test
    void assigneeTicksWithViewOnly() throws Exception {
        User viewer = brandUserWith("viewer", "CRM_VIEWER", Set.of("crm:view"));
        User viewer2 = brandUserWith("viewer2", "CRM_VIEWER_2", Set.of("crm:view"));
        assignPipelines(viewer, salesId());
        assignPipelines(viewer2, salesId());
        Map<String, Object> b = body("Onboard", steps("Send price list"));
        b.put("assigneeId", viewer.getId());
        JsonNode t = create(managerToken, b);
        String path = crm() + "/tasks/" + t.get("id").asText() + "/checklist/" + t.get("checklist").get(0).get("id").asText();
        send(post(path), testUsers.accessToken(viewer2), Map.of("done", true)).andExpect(status().isForbidden());
        send(post(path), testUsers.accessToken(viewer), Map.of("done", true)).andExpect(status().isOk())
                .andExpect(jsonPath("$.checklist[0].doneBy.id").value(viewer.getId()));
    }

    @Test
    void tickWritesNoTimelineAndKeepsCounters() throws Exception {
        JsonNode t = create(managerToken, body("Onboard", steps("Send price list")));
        long activities = mongo.count(new Query(Criteria.where("target.id").is(dealId)), CrmActivity.class);
        Document before = mongo.findOne(new Query(Criteria.where("_id").is(dealId)), Document.class, "crmDeals");
        tick(managerToken, t, 0, true);
        assertThat(mongo.count(new Query(Criteria.where("target.id").is(dealId)), CrmActivity.class)).isEqualTo(activities);
        Document after = mongo.findOne(new Query(Criteria.where("_id").is(dealId)), Document.class, "crmDeals");
        assertThat(after.get("nextTaskDueAt")).isEqualTo(before.get("nextTaskDueAt"));
        assertThat(after.get("version")).isEqualTo(before.get("version"));
    }

    @Test
    void over30StepsRejected() throws Exception {
        List<Map<String, String>> many = IntStream.range(0, 31).mapToObj(i -> Map.of("text", "Step " + i)).toList();
        send(post(crm() + "/tasks"), managerToken, body("Onboard", many)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.checklist").value("at most 30 items"));
        send(post(crm() + "/tasks"), managerToken, body("Onboard", steps("ok", " "))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields['checklist[1].text']").value("must be 1 to 200 characters"));
    }

}
