package me.nageshk.omnichannel.app.crm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

    // --- Task 3: breakdowns and the lifecycle funnel ------------------------------------------------------

    private void setDeal(String dealId, Object... kv) {
        Update u = new Update();
        for (int i = 0; i < kv.length; i += 2) u.set((String) kv[i], kv[i + 1]);
        mongo.updateFirst(new Query(Criteria.where("_id").is(new ObjectId(dealId))), u, "crmDeals");
    }

    private static JsonNode row(JsonNode rows, String field, String value) {
        for (JsonNode r : rows) if (value.equals(r.get(field).asText())) return r;
        throw new AssertionError("no row with " + field + "=" + value);
    }

    @Test
    void byOwnerListsUnassignedAndNames() throws Exception {
        enableCrm();
        String mine = dealOf("Asha", "919900000611", "1000", null).get("id").asText();
        String agents = dealOf("Ravi", "919900000612", "2000", null).get("id").asText();
        String nobody = dealOf("Meena", "919900000613", "300", null).get("id").asText();
        setDeal(agents, "ownerId", agent.getId());
        mongo.updateFirst(new Query(Criteria.where("_id").is(new ObjectId(nobody))), new Update().unset("ownerId"),
                "crmDeals");
        close(agents, "WON");
        JsonNode r = report("by-owner", managerToken, filter());
        JsonNode managerRow = row(r.get("rows"), "ownerId", manager.getId());
        assertThat(managerRow.get("label").asText()).isEqualTo(manager.getName());
        assertThat(managerRow.get("openCount").asLong()).isEqualTo(1);
        assertThat(managerRow.get("openAmount").asDouble()).isEqualTo(1000.0);
        JsonNode agentRow = row(r.get("rows"), "ownerId", agent.getId());
        assertThat(agentRow.get("wonCount").asLong()).isEqualTo(1);
        assertThat(agentRow.get("wonAmount").asDouble()).isEqualTo(2000.0);
        assertThat(agentRow.get("winRate").asDouble()).isEqualTo(1.0);
        JsonNode unassigned = row(r.get("rows"), "ownerId", "");
        assertThat(unassigned.get("label").asText()).isEqualTo("Unassigned");
        assertThat(unassigned.get("openCount").asLong()).isEqualTo(1);
        assertThat(mine).isNotNull();
    }

    @Test
    void bySourceLabelsPicklistAndOrigins() throws Exception {
        enableCrm();
        String web = dealOf("Asha", "919900000614", "500", null).get("id").asText();
        String blank = dealOf("Ravi", "919900000615", "100", null).get("id").asText();
        setDeal(web, "source", "website");
        close(web, "WON");
        send(post(crm() + "/contacts/create"), managerToken, Map.of("phone", "919900000616", "phoneNumberId",
                number.getId(), "firstName", "Manual", "pipelineId", salesId())).andExpect(status().isOk());
        JsonNode r = report("by-source", managerToken, filter());
        JsonNode website = row(r.get("deals"), "source", "website");
        assertThat(website.get("label").asText()).isEqualTo("Website");
        assertThat(website.get("created").asLong()).isEqualTo(1);
        assertThat(website.get("won").asLong()).isEqualTo(1);
        assertThat(website.get("wonAmount").asDouble()).isEqualTo(500.0);
        JsonNode unspecified = row(r.get("deals"), "source", "");
        assertThat(unspecified.get("label").asText()).isEqualTo("Unspecified");
        assertThat(unspecified.get("created").asLong()).isEqualTo(2); // the blank one and the manual contact's deal
        assertThat(r.get("contacts").size()).isEqualTo(7);
        assertThat(row(r.get("contacts"), "originKind", "INBOUND_CHAT").get("created").asLong()).isEqualTo(2);
        assertThat(row(r.get("contacts"), "originKind", "MANUAL").get("created").asLong()).isEqualTo(1);
        assertThat(row(r.get("contacts"), "originKind", "API").get("created").asLong()).isZero();
        assertThat(blank).isNotNull();
    }

    @Test
    void byNumberLabelsNumbers() throws Exception {
        enableCrm();
        String onSales = dealOf("Asha", "919900000617", "900", null).get("id").asText();
        JsonNode other = captured(contact("Ravi", "919900000618"), number2).get("deal");
        close(other.get("id").asText(), "WON");
        JsonNode r = report("by-number", managerToken, filter());
        JsonNode first = row(r.get("rows"), "phoneNumberId", number.getId());
        assertThat(first.get("label").asText()).isEqualTo(number.getNumber());
        assertThat(first.get("created").asLong()).isEqualTo(1);
        assertThat(first.get("openAmount").asDouble()).isEqualTo(900.0);
        JsonNode second = row(r.get("rows"), "phoneNumberId", number2.getId());
        assertThat(second.get("won").asLong()).isEqualTo(1);
        assertThat(onSales).isNotNull();
        // a number filter narrows the rows
        JsonNode only = report("by-number", managerToken, filter("phoneNumberIds", List.of(number2.getId())));
        assertThat(only.get("rows")).hasSize(1);
    }

    @Test
    void lifecycleFunnelOrdersPicklistAndNotSet() throws Exception {
        enableCrm();
        JsonNode a = captured(contact("Asha", "919900000619"), number).get("contact");
        captured(contact("Ravi", "919900000620"), number);
        mongo.updateFirst(new Query(Criteria.where("_id").is(new ObjectId(a.get("id").asText()))),
                new Update().set("lifecycleStatus", "lost"), "crmContacts");
        // "lost" switched off in the picklist: still listed, after the active ones
        mongo.updateFirst(new Query(Criteria.where("workspaceId").is(brand.getId())
                .and("lifecycleStatuses.key").is("lost")), new Update().set("lifecycleStatuses.$.active", false),
                "crmSettings");
        JsonNode r = report("lifecycle-funnel", managerToken, filter());
        List<String> keys = new java.util.ArrayList<>();
        r.get("rows").forEach(x -> keys.add(x.get("key").asText()));
        assertThat(keys).containsExactly("lead", "prospect", "customer", "lost", "");
        assertThat(row(r.get("rows"), "key", "lead").get("count").asLong()).isEqualTo(1);
        assertThat(row(r.get("rows"), "key", "lost").get("count").asLong()).isEqualTo(1);
        assertThat(row(r.get("rows"), "key", "lost").get("active").asBoolean()).isFalse();
        assertThat(row(r.get("rows"), "key", "").get("label").asText()).isEqualTo("Not set");
        assertThat(r.get("total").asLong()).isEqualTo(2);
    }

    @Test
    void otherCurrencyCountedNotSummed() throws Exception {
        enableCrm();
        String usd = dealOf("Asha", "919900000621", "100", "USD").get("id").asText();
        String inr = dealOf("Ravi", "919900000622", "400", null).get("id").asText();
        close(usd, "WON");
        close(inr, "WON");
        JsonNode r = report("by-owner", managerToken, filter());
        JsonNode mine = row(r.get("rows"), "ownerId", manager.getId());
        assertThat(mine.get("wonCount").asLong()).isEqualTo(2);
        assertThat(mine.get("wonAmount").asDouble()).isEqualTo(400.0);
        assertThat(r.get("otherCurrencyDeals").asLong()).isEqualTo(1);
        JsonNode summary = report("pipeline-summary", managerToken, filter());
        assertThat(summary.get("won").get("count").asLong()).isEqualTo(2);
        assertThat(summary.get("won").get("amount").asDouble()).isEqualTo(400.0);
        assertThat(summary.get("otherCurrencyDeals").asLong()).isEqualTo(1);
        // the average is over the deals whose money was summed, not over every won deal
        assertThat(summary.get("averageWonAmount").asDouble()).isEqualTo(400.0);
    }

    @Test
    void activitySummaryHonoursTheOwnerFilter() throws Exception {
        enableCrm();
        String deal = dealOf("Asha", "919900000641", "100", null).get("id").asText();
        log(managerToken, "DEAL", deal, "CALL");
        JsonNode mine = report("activity-summary", managerToken, filter("ownerIds", List.of(manager.getId())));
        assertThat(row(mine.get("rows"), "actorId", manager.getId()).get("calls").asLong()).isEqualTo(1);
        JsonNode theirs = report("activity-summary", managerToken, filter("ownerIds", List.of(agent.getId())));
        assertThat(theirs.get("rows")).isEmpty();
    }

    @Test
    void ownerFilterNarrowsEveryBreakdown() throws Exception {
        enableCrm();
        String agents = dealOf("Asha", "919900000623", "50", null).get("id").asText();
        dealOf("Ravi", "919900000624", "60", null);
        setDeal(agents, "ownerId", agent.getId());
        mongo.updateMulti(new Query(Criteria.where("workspaceId").is(brand.getId()).and("phone").is("919900000623")),
                new Update().set("ownerId", agent.getId()), "crmContacts");
        Map<String, Object> f = filter("ownerIds", List.of(agent.getId()));
        assertThat(report("by-owner", managerToken, f).get("rows")).hasSize(1);
        assertThat(report("by-number", managerToken, f).get("rows").get(0).get("openAmount").asDouble())
                .isEqualTo(50.0);
        assertThat(report("lifecycle-funnel", managerToken, f).get("total").asLong()).isEqualTo(1);
        JsonNode source = report("by-source", managerToken, f);
        assertThat(row(source.get("deals"), "source", "").get("created").asLong()).isEqualTo(1);
    }

    // --- Task 5: activity summary and stage duration -------------------------------------------------------

    private String task(String token, String dealId, String title) throws Exception {
        return json(send(post(crm() + "/tasks"), token, Map.of("title", title, "target",
                Map.of("type", "DEAL", "id", dealId))).andExpect(status().isOk())).get("id").asText();
    }

    private void log(String token, String type, String id, String kind) throws Exception {
        send(post(crm() + "/activities/log"), token, Map.of("target", Map.of("type", type, "id", id), "type", kind,
                "summary", kind + " done")).andExpect(status().isOk());
    }

    @Test
    void activitySummaryCountsPerPerson() throws Exception {
        enableCrm();
        assignPipelines(agent, salesId());
        Map<String, String> stages = stages(salesId());
        String deal = dealOf("Asha", "919900000631", "100", null).get("id").asText();
        String lostOne = dealOf("Ravi", "919900000632", "50", null).get("id").asText();
        String taskId = task(managerToken, deal, "Call back");
        send(post(crm() + "/tasks/" + taskId + "/complete"), managerToken, null).andExpect(status().isOk());
        send(post(crm() + "/notes"), managerToken, Map.of("target", Map.of("type", "DEAL", "id", deal), "body",
                "Spoke to Asha")).andExpect(status().isOk());
        log(managerToken, "DEAL", deal, "CALL");
        move(deal, stages.get("Contacted"));
        close(deal, "WON");
        close(lostOne, "LOST");
        log(agentToken, "DEAL", lostOne, "MEETING");
        log(agentToken, "DEAL", lostOne, "EMAIL");

        JsonNode r = report("activity-summary", managerToken, filter());
        JsonNode me = row(r.get("rows"), "actorId", manager.getId());
        assertThat(me.get("label").asText()).isEqualTo(manager.getName());
        assertThat(me.get("tasksCompleted").asLong()).isEqualTo(1);
        assertThat(me.get("notesAdded").asLong()).isEqualTo(1);
        assertThat(me.get("calls").asLong()).isEqualTo(1);
        assertThat(me.get("stageMoves").asLong()).isEqualTo(3); // move, close WON, close LOST
        assertThat(me.get("dealsWon").asLong()).isEqualTo(1);
        assertThat(me.get("dealsLost").asLong()).isEqualTo(1);
        JsonNode them = row(r.get("rows"), "actorId", agent.getId());
        assertThat(them.get("meetings").asLong()).isEqualTo(1);
        assertThat(them.get("emails").asLong()).isEqualTo(1);
        assertThat(them.get("tasksCompleted").asLong()).isZero();
        assertThat(r.get("rows").get(0).get("actorId").asText()).isEqualTo(manager.getId()); // busiest first
    }

    @Test
    void activitiesWithoutPipelineExcludedForRestricted() throws Exception {
        enableCrm();
        assignPipelines(agent, salesId());
        String company = json(send(post(crm() + "/companies"), managerToken, Map.of("name", "Acme"))
                .andExpect(status().isOk())).get("id").asText();
        // a company note carries no pipeline
        send(post(crm() + "/notes"), managerToken, Map.of("target", Map.of("type", "COMPANY", "id", company),
                "body", "Brand-wide note")).andExpect(status().isOk());
        String deal = dealOf("Asha", "919900000633", "10", null).get("id").asText();
        log(managerToken, "DEAL", deal, "CALL");

        JsonNode mine = row(report("activity-summary", managerToken, filter()).get("rows"), "actorId",
                manager.getId());
        assertThat(mine.get("notesAdded").asLong()).isEqualTo(1);
        assertThat(mine.get("calls").asLong()).isEqualTo(1);
        JsonNode seenByAgent = row(report("activity-summary", agentToken, filter()).get("rows"), "actorId",
                manager.getId());
        assertThat(seenByAgent.get("notesAdded").asLong()).isZero();
        assertThat(seenByAgent.get("calls").asLong()).isEqualTo(1);
    }

    @Test
    void stageDurationFromMoves() throws Exception {
        enableCrm();
        Map<String, String> stages = stages(salesId());
        String a = dealOf("Asha", "919900000634", "1", null).get("id").asText();
        String b = dealOf("Ravi", "919900000635", "1", null).get("id").asText();
        String stays = dealOf("Meena", "919900000636", "1", null).get("id").asText();
        // a: 4 days in New, then 2 days in Contacted; b: 8 days in New
        backdate("crmDeals", a, "stageEnteredAt", daysAgo(4));
        move(a, stages.get("Contacted"));
        backdate("crmDeals", a, "stageEnteredAt", daysAgo(2));
        move(a, stages.get("Qualified"));
        backdate("crmDeals", b, "stageEnteredAt", daysAgo(8));
        move(b, stages.get("Contacted"));
        backdate("crmDeals", stays, "stageEnteredAt", daysAgo(10));

        JsonNode r = report("stage-duration", managerToken, filter());
        assertThat(r.get("pipelineName").asText()).isEqualTo("Sales");
        assertThat(r.get("since").isNull()).isFalse();
        JsonNode newStage = row(r.get("stages"), "name", "New");
        assertThat(newStage.get("movesOut").asLong()).isEqualTo(2);
        assertThat(newStage.get("averageDays").asDouble()).isEqualTo(6.0);
        assertThat(newStage.get("medianDays").asDouble()).isEqualTo(6.0);
        assertThat(newStage.get("openNow").asLong()).isEqualTo(1);
        assertThat(newStage.get("averageDaysOpenNow").asDouble()).isEqualTo(10.0);
        JsonNode contacted = row(r.get("stages"), "name", "Contacted");
        assertThat(contacted.get("movesOut").asLong()).isEqualTo(1);
        assertThat(contacted.get("averageDays").asDouble()).isEqualTo(2.0);
        assertThat(contacted.get("openNow").asLong()).isEqualTo(1);
        JsonNode qualified = row(r.get("stages"), "name", "Qualified");
        assertThat(qualified.get("movesOut").asLong()).isZero();
        assertThat(qualified.get("averageDays").isNull()).isTrue();
        assertThat(qualified.get("openNow").asLong()).isEqualTo(1);
    }

    @Test
    void legacyMovesIgnored() throws Exception {
        enableCrm();
        Map<String, String> stages = stages(salesId());
        String a = dealOf("Asha", "919900000637", "1", null).get("id").asText();
        backdate("crmDeals", a, "stageEnteredAt", daysAgo(3));
        move(a, stages.get("Contacted"));
        mongo.updateFirst(new Query(Criteria.where("target.id").is(a).and("type").is("STAGE_CHANGED")),
                new Update().unset("payload.fromStageEnteredAt"), "crmActivities");
        JsonNode r = report("stage-duration", managerToken, filter());
        assertThat(row(r.get("stages"), "name", "New").get("movesOut").asLong()).isZero();
        assertThat(r.get("since").isNull()).isTrue();
        String b = dealOf("Ravi", "919900000638", "1", null).get("id").asText();
        backdate("crmDeals", b, "stageEnteredAt", daysAgo(1));
        move(b, stages.get("Contacted"));
        JsonNode again = report("stage-duration", managerToken, filter());
        assertThat(row(again.get("stages"), "name", "New").get("movesOut").asLong()).isEqualTo(1);
        assertThat(Instant.parse(again.get("since").asText())).isAfter(daysAgo(1).minus(1, ChronoUnit.MINUTES));
    }

    @Test
    void newIndexesLeadWithWorkspace() {
        enableCrm();
        for (String[] ix : new String[][] {{"crmDeals", "ws_created"}, {"crmContacts", "ws_created"},
                {"crmActivities", "ws_type_happened"}}) {
            boolean found = false;
            for (org.bson.Document d : mongo.getCollection(ix[0]).listIndexes()) {
                if (ix[1].equals(d.getString("name"))) {
                    found = true;
                    assertThat(((org.bson.Document) d.get("key")).keySet().iterator().next()).isEqualTo("workspaceId");
                }
            }
            assertThat(found).as(ix[0] + "." + ix[1]).isTrue();
        }
    }

    // --- Task 6: grid ----------------------------------------------------------------------------------------

    private Map<String, Object> grid(String entity, String groupBy, Object... kv) {
        Map<String, Object> m = filter("entityType", entity, "groupBy", groupBy);
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private void field(String entity, String key, String type, List<Map<String, String>> options) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("entityType", entity, "key", key, "label",
                key.toUpperCase(), "type", type));
        if (options != null) body.put("options", options);
        send(post(crm() + "/fields"), managerToken, body).andExpect(status().isCreated());
    }

    @Test
    void gridCountsDealsByStage() throws Exception {
        enableCrm();
        Map<String, String> stages = stages(salesId());
        dealOf("Asha", "919900000641", "1", null);
        String b = dealOf("Ravi", "919900000642", "1", null).get("id").asText();
        move(b, stages.get("Qualified"));
        JsonNode r = report("grid", managerToken, grid("DEAL", "stage", "pipelineId", salesId()));
        assertThat(r.get("metric").asText()).isEqualTo("COUNT");
        assertThat(r.get("truncated").asBoolean()).isFalse();
        assertThat(row(r.get("rows"), "label", "New").get("count").asLong()).isEqualTo(1);
        assertThat(row(r.get("rows"), "label", "Qualified").get("value").asDouble()).isEqualTo(1.0);
        assertThat(row(r.get("rows"), "label", "Qualified").get("key").asText()).isEqualTo(stages.get("Qualified"));
    }

    @Test
    void gridSumsAmountBySourcePerMonth() throws Exception {
        enableCrm();
        String a = dealOf("Asha", "919900000643", "100", null).get("id").asText();
        String b = dealOf("Ravi", "919900000644", "250", null).get("id").asText();
        String usd = dealOf("Meena", "919900000645", "999", "USD").get("id").asText();
        setDeal(a, "source", "website");
        setDeal(b, "source", "website");
        setDeal(usd, "source", "website");
        backdate("crmDeals", a, "creationTime", daysAgo(45));
        JsonNode r = report("grid", managerToken, grid("DEAL", "source", "metric", "SUM", "field", "amount",
                "dateBucket", "MONTH"));
        assertThat(r.get("currency").asText()).isEqualTo("INR");
        assertThat(r.get("otherCurrencyDeals").asLong()).isEqualTo(1);
        double total = 0;
        int websiteRows = 0;
        for (JsonNode x : r.get("rows")) {
            if ("website".equals(x.get("key").asText())) {
                websiteRows++;
                assertThat(x.get("label").asText()).isEqualTo("Website");
                assertThat(x.get("bucket").isNull()).isFalse();
                total += x.get("value").asDouble();
            }
        }
        assertThat(websiteRows).isGreaterThanOrEqualTo(1);
        assertThat(total).isEqualTo(350.0);
    }

    @Test
    void gridGroupsContactsByLifecycleAndCompaniesByIndustry() throws Exception {
        enableCrm();
        captured(contact("Asha", "919900000646"), number);
        captured(contact("Ravi", "919900000647"), number);
        send(post(crm() + "/companies"), managerToken, Map.of("name", "A", "industry", "mining"))
                .andExpect(status().isOk());
        send(post(crm() + "/companies"), managerToken, Map.of("name", "B")).andExpect(status().isOk());
        JsonNode c = report("grid", managerToken, grid("CONTACT", "lifecycle"));
        assertThat(row(c.get("rows"), "key", "lead").get("label").asText()).isEqualTo("Lead");
        assertThat(row(c.get("rows"), "key", "lead").get("count").asLong()).isEqualTo(2);
        JsonNode co = report("grid", managerToken, grid("COMPANY", "industry"));
        assertThat(row(co.get("rows"), "key", "mining").get("count").asLong()).isEqualTo(1);
        assertThat(row(co.get("rows"), "key", "").get("label").asText()).isEqualTo("Not set");
    }

    @Test
    void gridCustomFieldGroupAndMetric() throws Exception {
        enableCrm();
        field("DEAL", "tier", "SELECT", List.of(Map.of("key", "gold", "label", "Gold"),
                Map.of("key", "silver", "label", "Silver")));
        field("DEAL", "rush", "BOOLEAN", null);
        field("DEAL", "budget", "CURRENCY", null);
        String a = dealOf("Asha", "919900000648", "1", null).get("id").asText();
        String b = dealOf("Ravi", "919900000649", "1", null).get("id").asText();
        setDeal(a, "customFields", Map.of("tier", "gold", "rush", true, "budget", 100L));
        setDeal(b, "customFields", Map.of("tier", "gold", "rush", false, "budget", 300L));
        JsonNode tier = report("grid", managerToken, grid("DEAL", "custom:tier"));
        assertThat(row(tier.get("rows"), "key", "gold").get("label").asText()).isEqualTo("Gold");
        assertThat(row(tier.get("rows"), "key", "gold").get("count").asLong()).isEqualTo(2);
        JsonNode rush = report("grid", managerToken, grid("DEAL", "custom:rush"));
        assertThat(row(rush.get("rows"), "key", "true").get("label").asText()).isEqualTo("Yes");
        assertThat(row(rush.get("rows"), "key", "false").get("label").asText()).isEqualTo("No");
        JsonNode avg = report("grid", managerToken, grid("DEAL", "custom:tier", "metric", "AVG", "field",
                "custom:budget"));
        assertThat(row(avg.get("rows"), "key", "gold").get("value").asDouble()).isEqualTo(200.0);
        assertThat(avg.get("field").asText()).isEqualTo("custom:budget");
    }

    @Test
    void gridFoldsBeyond100Groups() throws Exception {
        enableCrm();
        List<org.bson.Document> docs = new java.util.ArrayList<>();
        for (int i = 0; i < 101; i++) {
            docs.add(new org.bson.Document("workspaceId", brand.getId()).append("customerId", acme.getId())
                    .append("name", "Co " + i).append("size", "size-" + String.format("%03d", i))
                    .append("creationTime", java.util.Date.from(daysAgo(1))).append("version", 0L)
                    .append("_class", "me.nageshk.omnichannel.crm.company.CrmCompany"));
        }
        mongo.getCollection("crmCompanies").insertMany(docs);
        JsonNode r = report("grid", managerToken, grid("COMPANY", "size"));
        assertThat(r.get("truncated").asBoolean()).isTrue();
        assertThat(r.get("rows")).hasSize(101);
        JsonNode other = r.get("rows").get(100);
        assertThat(other.get("key").asText()).isEqualTo("__other");
        assertThat(other.get("label").asText()).isEqualTo("Other");
        assertThat(other.get("count").asLong()).isEqualTo(1);
    }

    @Test
    void gridRefusesBadFields() throws Exception {
        enableCrm();
        field("DEAL", "tags", "MULTI_SELECT", List.of(Map.of("key", "a", "label", "A")));
        field("DEAL", "old", "TEXT", null);
        JsonNode defs = json(send(get(crm() + "/fields?entityType=DEAL"), managerToken, null));
        String oldId = row(defs, "key", "old").get("id").asText();
        send(delete(crm() + "/fields/" + oldId), managerToken, null).andExpect(status().isNoContent());
        Object[][] bad = {
            {grid("DEAL", "stage"), "pipelineId"},
            {grid("DEAL", "custom:tags"), "groupBy"},
            {grid("DEAL", "custom:old"), "groupBy"},
            {grid("DEAL", "custom:nope"), "groupBy"},
            {grid("CONTACT", "owner", "dateField", "closedAt"), "dateField"},
            {grid("DEAL", "owner", "metric", "MAX"), "metric"},
            {grid("DEAL", "owner", "metric", "SUM"), "field"},
            {grid("DEAL", "owner", "metric", "SUM", "field", "custom:tags"), "field"},
            {grid("DEAL", "owner", "dateBucket", "HOUR"), "dateBucket"},
            {grid("PIPELINE", "owner"), "entityType"},
        };
        for (Object[] b : bad) {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) b[0];
            send(post(reports() + "/grid"), managerToken, body).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REPORT_FILTER"))
                    .andExpect(jsonPath("$.field").value(b[1]));
        }
    }

    // --- Task 7: campaigns card ------------------------------------------------------------------------------

    private String campaignWith(String name, Instant startedAt, List<String> recipientContactIds) {
        // one sender per number (unique index): reuse it across the test's campaigns
        var sender = mongo.findOne(new Query(Criteria.where("phoneNumberId").is(number.getId())),
                me.nageshk.omnichannel.db.campaign.CampaignSender.class);
        if (sender == null) {
            sender = new me.nageshk.omnichannel.db.campaign.CampaignSender();
            sender.setWorkspaceId(brand.getId());
            sender.setPhoneNumberId(number.getId());
            sender = mongo.insert(sender);
        }
        var c = new me.nageshk.omnichannel.db.campaign.Campaign();
        c.setWorkspaceId(brand.getId());
        c.setCustomerId(acme.getId());
        c.setName(name);
        c.setNameKey(name.toLowerCase() + "-" + System.nanoTime());
        c.setSenderIds(List.of(sender.getId()));
        c.setStatus(me.nageshk.omnichannel.db.campaign.CampaignStatus.COMPLETED);
        c.setCounts(new me.nageshk.omnichannel.db.campaign.Campaign.Counts(recipientContactIds.size(), 0,
                recipientContactIds.size(), recipientContactIds.size(), 1, 0, 0, 0));
        c.setCreationTime(startedAt);
        c.setStartedAt(startedAt);
        c = mongo.insert(c);
        int i = 0;
        for (String contactId : recipientContactIds) {
            var r = new me.nageshk.omnichannel.db.campaign.CampaignRecipient();
            r.setCampaignId(c.getId());
            r.setWorkspaceId(brand.getId());
            r.setPhone("91980000" + (1000 + i++));
            r.setContactId(contactId);
            r.setStatus(me.nageshk.omnichannel.db.campaign.RecipientStatus.DELIVERED);
            mongo.insert(r);
        }
        return c.getId();
    }

    @Test
    void campaignsCardCountsCrmContactsReached() throws Exception {
        enableCrm();
        String second = json(send(post(crm() + "/pipelines"), managerToken, Map.of("name", "Second"))
                .andExpect(status().isCreated())).get("id").asText();
        JsonNode a = captured(contact("Asha", "919900000651"), number).get("contact");
        JsonNode b = captured(contact("Ravi", "919900000652"), number).get("contact");
        String reached = campaignWith("Festive", daysAgo(2),
                List.of(a.get("contactId").asText(), b.get("contactId").asText(), "someone-else"));
        campaignWith("Nobody in CRM", daysAgo(3), List.of("stranger"));
        JsonNode r = report("campaigns", managerToken, filter());
        assertThat(r.get("truncated").asBoolean()).isFalse();
        assertThat(r.get("rows")).hasSize(2);
        JsonNode festive = row(r.get("rows"), "campaignId", reached);
        assertThat(festive.get("name").asText()).isEqualTo("Festive");
        assertThat(festive.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(festive.get("recipients").asLong()).isEqualTo(3);
        assertThat(festive.get("delivered").asLong()).isEqualTo(3);
        assertThat(festive.get("read").asLong()).isEqualTo(1);
        assertThat(festive.get("crmContactsReached").asLong()).isEqualTo(2);
        assertThat(festive.get("numbers").get(0).get("label").asText()).isEqualTo(number.getNumber());
        // an agent restricted to a pipeline with none of those contacts sees no row
        assignPipelines(agent, second);
        assertThat(report("campaigns", agentToken, filter()).get("rows")).isEmpty();
        assignPipelines(agent, salesId());
        JsonNode mine = report("campaigns", agentToken, filter());
        assertThat(mine.get("rows")).hasSize(1);
        assertThat(mine.get("rows").get(0).get("crmContactsReached").asLong()).isEqualTo(2);
    }

    @Test
    void campaignsCardEmptyWhenNoneStarted() throws Exception {
        enableCrm();
        campaignWith("Old", daysAgo(200), List.of("x"));
        JsonNode r = report("campaigns", managerToken, filter());
        assertThat(r.get("rows")).isEmpty();
        assertThat(r.get("truncated").asBoolean()).isFalse();
    }
}
