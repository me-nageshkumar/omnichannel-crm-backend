package me.nageshk.omnichannel.app.crm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
}
