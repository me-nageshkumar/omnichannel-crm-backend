package me.nageshk.omnichannel.app.crm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import tools.jackson.databind.JsonNode;

/** The reports (spec phase C); records are seeded through the API and backdated by collection name. */
class ReportsApiIT extends CrmTestBase {

    private static final String TZ = "Asia/Kolkata";

    private static Instant daysAgo(int days) {
        return Instant.now().minus(days, ChronoUnit.DAYS);
    }

    private String reports() {
        return crm() + "/reports";
    }

    private Map<String, Object> filter(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tz", TZ);
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private JsonNode report(String name, String token, Map<String, Object> body) throws Exception {
        return json(send(post(reports() + "/" + name), token, body).andExpect(status().isOk()));
    }

    /** A captured deal with an amount (and currency) set through the API. */
    private JsonNode dealOf(String name, String phone, String amount, String currency) throws Exception {
        JsonNode deal = captured(contact(name, phone), number).get("deal");
        Map<String, Object> body = new HashMap<>();
        body.put("version", deal.get("version").asLong());
        if (amount != null) body.put("amount", amount);
        if (currency != null) body.put("currency", currency);
        return json(send(put(crm() + "/deals/" + deal.get("id").asText()), managerToken, body)
                .andExpect(status().isOk()));
    }

    private void move(String dealId, String stageId) throws Exception {
        send(post(crm() + "/deals/" + dealId + "/move"), managerToken, Map.of("stageId", stageId))
                .andExpect(status().isOk());
    }

    private void close(String dealId, String outcome) throws Exception {
        send(post(crm() + "/deals/" + dealId + "/close"), managerToken, Map.of("outcome", outcome))
                .andExpect(status().isOk());
    }

    private void backdate(String collection, String id, String field, Instant at) {
        mongo.updateFirst(new Query(Criteria.where("_id").is(new ObjectId(id))), new Update().set(field, at),
                collection);
    }

    // --- Task 2: pipeline summary, deals over time, filter ---------------------------------------------------

    @Test
    void pipelineSummaryCountsOpenAndClosed() throws Exception {
        enableCrm();
        Map<String, String> stages = stages(salesId());
        String open1 = dealOf("Asha", "919900000601", "1000", null).get("id").asText();
        String open2 = dealOf("Ravi", "919900000602", "3000", null).get("id").asText();
        String won = dealOf("Meena", "919900000603", "5000", null).get("id").asText();
        String lost = dealOf("Nila", "919900000604", "700", null).get("id").asText();
        move(open2, stages.get("Qualified"));
        close(won, "WON");
        close(lost, "LOST");

        JsonNode r = report("pipeline-summary", managerToken, filter());
        assertThat(r.get("pipelineName").asText()).isEqualTo("Sales");
        assertThat(r.get("currency").asText()).isEqualTo("INR");
        List<String> names = new java.util.ArrayList<>();
        r.get("stages").forEach(s -> names.add(s.get("name").asText()));
        assertThat(names).containsExactly("New", "Contacted", "Qualified", "Proposal", "Negotiation", "Won", "Lost");
        JsonNode first = r.get("stages").get(0);
        assertThat(first.get("openCount").asLong()).isEqualTo(1);
        assertThat(first.get("openAmount").asDouble()).isEqualTo(1000.0);
        assertThat(first.get("weightedAmount").asDouble()).isEqualTo(100.0); // New is 10%
        JsonNode qualified = r.get("stages").get(2);
        assertThat(qualified.get("openAmount").asDouble()).isEqualTo(3000.0);
        assertThat(qualified.get("weightedAmount").asDouble()).isEqualTo(1500.0); // 50%
        assertThat(r.get("openCount").asLong()).isEqualTo(2);
        assertThat(r.get("openAmount").asDouble()).isEqualTo(4000.0);
        assertThat(r.get("won").get("count").asLong()).isEqualTo(1);
        assertThat(r.get("won").get("amount").asDouble()).isEqualTo(5000.0);
        assertThat(r.get("lost").get("count").asLong()).isEqualTo(1);
        assertThat(r.get("winRate").asDouble()).isEqualTo(0.5);
        assertThat(r.get("averageWonAmount").asDouble()).isEqualTo(5000.0);
        assertThat(r.get("otherCurrencyDeals").asLong()).isZero();
        assertThat(open1).isNotNull();
    }

    @Test
    void pipelineSummaryDefaultsToDefaultPipeline() throws Exception {
        enableCrm();
        send(post(crm() + "/pipelines"), managerToken, Map.of("name", "Second")).andExpect(status().isCreated());
        JsonNode r = report("pipeline-summary", managerToken, filter());
        assertThat(r.get("pipelineId").asText()).isEqualTo(salesId());
        assertThat(r.get("winRate").isNull()).isTrue();
        assertThat(r.get("averageWonAmount").isNull()).isTrue();
    }

    @Test
    void restrictedCallerSeesOnlyTheirPipeline() throws Exception {
        enableCrm();
        String second = json(send(post(crm() + "/pipelines"), managerToken, Map.of("name", "Second"))
                .andExpect(status().isCreated())).get("id").asText();
        JsonNode capture = captured(contact("Asha", "919900000605"), number);
        createDeal(Map.of("title", "In second", "pipelineId", second, "primaryCrmContactId",
                capture.get("contact").get("id").asText(), "amount", "999"));
        assignPipelines(agent, second);

        send(post(reports() + "/pipeline-summary"), agentToken, filter("pipelineId", salesId()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REPORT_FILTER"))
                .andExpect(jsonPath("$.field").value("pipelineId"));
        JsonNode mine = report("pipeline-summary", agentToken, filter());
        assertThat(mine.get("pipelineId").asText()).isEqualTo(second);
        assertThat(mine.get("openCount").asLong()).isEqualTo(1);
        JsonNode over = report("deals-over-time", agentToken, filter());
        long created = 0;
        for (JsonNode p : over.get("points")) created += p.get("created").asLong();
        assertThat(created).isEqualTo(1);
        JsonNode all = report("deals-over-time", managerToken, filter());
        long allCreated = 0;
        for (JsonNode p : all.get("points")) allCreated += p.get("created").asLong();
        assertThat(allCreated).isEqualTo(2);
    }

    @Test
    void dealsOverTimeZeroFillsWeeks() throws Exception {
        enableCrm();
        String a = dealOf("Asha", "919900000606", "100", null).get("id").asText();
        String b = dealOf("Ravi", "919900000607", "250", null).get("id").asText();
        backdate("crmDeals", a, "creationTime", daysAgo(40));
        close(b, "WON");
        backdate("crmDeals", b, "closedAt", daysAgo(20));
        Instant from = daysAgo(60);
        JsonNode r = report("deals-over-time", managerToken,
                filter("from", from.toString(), "to", Instant.now().toString()));
        assertThat(r.get("bucket").asText()).isEqualTo("WEEK");
        assertThat(r.get("points").size()).isBetween(9, 10);
        Instant previous = null;
        long created = 0, won = 0;
        double wonAmount = 0;
        for (JsonNode p : r.get("points")) {
            Instant start = Instant.parse(p.get("start").asText());
            if (previous != null) assertThat(start).isEqualTo(previous.plus(7, ChronoUnit.DAYS));
            previous = start;
            // every start is a Monday 00:00 in Kolkata
            assertThat(start.atZone(java.time.ZoneId.of(TZ)).getDayOfWeek()).isEqualTo(java.time.DayOfWeek.MONDAY);
            assertThat(start.atZone(java.time.ZoneId.of(TZ)).toLocalTime()).isEqualTo(java.time.LocalTime.MIDNIGHT);
            created += p.get("created").asLong();
            won += p.get("won").asLong();
            wonAmount += p.get("wonAmount").asDouble();
        }
        assertThat(created).isEqualTo(2);
        assertThat(won).isEqualTo(1);
        assertThat(wonAmount).isEqualTo(250.0);
    }

    @Test
    void dealsOverTimeBucketOverride() throws Exception {
        enableCrm();
        JsonNode r = report("deals-over-time", managerToken, filter("bucket", "DAY"));
        assertThat(r.get("bucket").asText()).isEqualTo("DAY");
        assertThat(r.get("points").size()).isBetween(90, 92);
        send(post(reports() + "/deals-over-time"), managerToken, filter("bucket", "HOUR"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("bucket"));
    }

    @Test
    void badFilterIs400() throws Exception {
        enableCrm();
        send(post(reports() + "/pipeline-summary"), managerToken, Map.of("tz", "+05:30"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REPORT_FILTER"))
                .andExpect(jsonPath("$.field").value("tz"));
        send(post(reports() + "/pipeline-summary"), managerToken,
                Map.of("from", "2026-10-30T00:00:00Z", "to", "2026-10-01T00:00:00Z"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("from"));
        // no body at all is the default filter
        send(post(reports() + "/pipeline-summary"), managerToken, null).andExpect(status().isOk());
    }

    @Test
    void userWithoutViewIsRefused() throws Exception {
        enableCrm();
        // the platform user holds no brand authority: refused like every brand CRM call (4xx, never data)
        send(post(reports() + "/pipeline-summary"), staffToken, filter()).andExpect(status().is4xxClientError());
        // an agent with no pipeline assigned has no stage-based report to show (spec C3)
        send(post(reports() + "/pipeline-summary"), agentToken, filter()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("pipelineId"));
        assignPipelines(agent, salesId());
        send(post(reports() + "/pipeline-summary"), agentToken, filter()).andExpect(status().isOk());
    }
}
