package me.nageshk.omnichannel.app.automation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import me.nageshk.omnichannel.crm.deal.CrmDeal;
import me.nageshk.omnichannel.db.automation.AutomationAction;
import me.nageshk.omnichannel.db.automation.AutomationActionType;
import me.nageshk.omnichannel.db.automation.AutomationEventKind;
import me.nageshk.omnichannel.db.automation.AutomationEventRecord;
import me.nageshk.omnichannel.db.automation.AutomationRule;
import me.nageshk.omnichannel.db.automation.EventData;
import me.nageshk.omnichannel.db.automation.EventOutcome;
import me.nageshk.omnichannel.db.automation.RuleStatus;
import me.nageshk.omnichannel.db.template.MessageTemplate;
import me.nageshk.omnichannel.db.template.TemplateStatus;
import me.nageshk.omnichannel.db.access.Role;
import me.nageshk.omnichannel.db.access.RoleLevel;
import me.nageshk.omnichannel.db.access.RoleRepository;
import me.nageshk.omnichannel.db.user.User;
import me.nageshk.omnichannel.db.user.WorkspaceRole;
import me.nageshk.omnichannel.service.automation.AutomationWorker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/** The runs of a rule and the history of events, newest first, as the console reads them. */
class AutomationHistoryApiIT extends AutomationTestBase {

    private static final String ASHA = "919811100001@c.us";
    private static final String RAVI = "919811100002@c.us";

    @Autowired AutomationWorker worker;
    @Autowired RoleRepository roles;

    private AutomationRule welcome;
    private AutomationRule leads;
    private AutomationRule thanks;
    private Instant t0;
    private String ashaContactId;
    private String ashaDealId;

    // ---- helpers ----

    private static AutomationAction send(String templateId) {
        return new AutomationAction(AutomationActionType.SEND_TEMPLATE, templateId, null, null, null, null, null,
                null, null, null);
    }

    private static AutomationAction addToCrm() {
        return new AutomationAction(AutomationActionType.ADD_TO_CRM, null, null, null, null, null, null, null, null,
                null);
    }

    /**
     * Four events, an hour apart: Asha "Hello" (Welcome and Leads run), Asha "Again" (Welcome only), Ravi "Hi" (Leads
     * only) and the close of Asha's deal as won (Thanks).
     */
    private void seed(AutomationDevice d) {
        enableCrm();
        call(HttpMethod.GET, "/api/workspaces/" + brand.getId() + "/crm/pipelines", manager, null);
        String w = template("Welcome", "Hi {{contact.name}}, welcome!");
        String ty = template("Thanks", "Thank you!");
        welcome = rule("Welcome", AutomationEventKind.MESSAGE_RECEIVED, RuleStatus.PUBLISHED, 1,
                leaf("contact.name", "is", "Asha"), send(w));
        leads = rule("Leads", AutomationEventKind.MESSAGE_RECEIVED, RuleStatus.PUBLISHED, 2,
                all(leaf("chat.new", "is", true)), addToCrm());
        thanks = rule("Thanks", AutomationEventKind.DEAL_LANE_CHANGED, RuleStatus.PUBLISHED, null,
                all(leaf("toLane.type", "is", "WON")), send(ty));
        t0 = clock.instant();
        ashaContactId = incoming(n1, ASHA, "chat", "Hello", "Asha").getContactId();
        worker.tick();
        clock.advance(Duration.ofHours(1));
        incoming(n1, ASHA, "chat", "Again", "Asha");
        worker.tick();
        clock.advance(Duration.ofHours(1));
        incoming(n1, RAVI, "chat", "Hi", "Ravi");
        worker.tick();
        clock.advance(Duration.ofHours(1));
        CrmDeal deal = mongo.findAll(CrmDeal.class).stream().filter(x -> x.getTitle().startsWith("Asha")).findFirst()
                .orElseThrow();
        ashaDealId = deal.getId();
        ResponseEntity<JsonNode> closed = call(HttpMethod.POST,
                "/api/workspaces/" + brand.getId() + "/crm/deals/" + deal.getId() + "/close", manager,
                Map.of("outcome", "WON"));
        assertThat(closed.getStatusCode()).isEqualTo(HttpStatus.OK);
        worker.tick();
    }

    private AutomationDevice device() throws Exception {
        return new AutomationDevice(port, testDevices, n1);
    }

    private JsonNode ok(ResponseEntity<JsonNode> res) {
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        return res.getBody();
    }

    private JsonNode runs(AutomationRule r, String query) {
        return ok(api(HttpMethod.GET, "/rules/" + r.getId() + "/runs" + query, manager, null));
    }

    private JsonNode events(String query) {
        return ok(api(HttpMethod.GET, "/events" + query, manager, null));
    }

    private static List<String> summaries(JsonNode page) {
        List<String> out = new ArrayList<>();
        page.get("items").forEach(i -> out.add(i.get("summary").asString()));
        return out;
    }

    private AutomationEventRecord record(String id, Instant createdAt, EventOutcome outcome, String error) {
        AutomationEventRecord r = new AutomationEventRecord();
        r.setId(id);
        r.setCustomerId(acme.getId());
        r.setWorkspaceId(brand.getId());
        r.setKind(AutomationEventKind.MESSAGE_RECEIVED);
        r.setData(new EventData(null, new EventData.MessageData("m", "Old news", "TEXT"), null,
                new EventData.ContactData("c", "Old", "919000000000", false, false), null, null, null, null));
        r.setContactId("c");
        r.setOutcome(outcome);
        r.setError(error);
        r.setAttempts(1);
        r.setRuleIds(List.of());
        r.setTestIds(List.of());
        r.setCreatedAt(createdAt);
        r.setFinishedAt(createdAt);
        return mongo.save(r);
    }

    // ---- tests ----

    @Test
    void runsNewestFirstWithSteps() throws Exception {
        try (AutomationDevice d = device()) {
            seed(d);
            JsonNode page = runs(welcome, "");
            assertThat(page.get("total").asInt()).isEqualTo(2);
            assertThat(page.get("page").asInt()).isZero();
            JsonNode items = page.get("items");
            assertThat(items).hasSize(2);
            JsonNode newest = items.get(0);
            assertThat(newest.get("summary").asString()).isEqualTo("Message from Asha: Again");
            assertThat(items.get(1).get("summary").asString()).isEqualTo("Message from Asha: Hello");
            JsonNode run = items.get(1);
            assertThat(run.get("id").asString()).isNotBlank();
            assertThat(run.get("eventId").asString()).isNotBlank();
            assertThat(run.get("ruleId").asString()).isEqualTo(welcome.getId());
            assertThat(run.get("ruleName").asString()).isEqualTo("Welcome");
            assertThat(run.get("test").asBoolean()).isFalse();
            assertThat(run.get("status").asString()).isEqualTo("DONE");
            assertThat(run.get("eventKind").asString()).isEqualTo("MESSAGE_RECEIVED");
            assertThat(run.get("contact").get("id").asString()).isEqualTo(ashaContactId);
            assertThat(run.get("contact").get("name").asString()).isEqualTo("Asha");
            assertThat(run.get("contact").get("phone").asString()).isEqualTo("919811100001");
            assertThat(run.get("deal").isNull()).isTrue();
            assertThat(Instant.parse(run.get("startedAt").asString())).isEqualTo(t0);
            assertThat(Instant.parse(run.get("finishedAt").asString())).isEqualTo(t0);
            assertThat(run.get("steps")).hasSize(1);
            JsonNode step = run.get("steps").get(0);
            assertThat(step.get("index").asInt()).isZero();
            assertThat(step.get("type").asString()).isEqualTo("SEND_TEMPLATE");
            assertThat(step.get("summary").asString()).isEqualTo("Send “Welcome”");
            assertThat(step.get("outcome").asString()).isEqualTo("DONE");
            assertThat(step.get("reason").asString()).isEqualTo("Sent “Welcome”");
            assertThat(Instant.parse(step.get("at").asString())).isEqualTo(t0);

            JsonNode lane = runs(thanks, "").get("items").get(0);
            assertThat(lane.get("deal").get("id").asString()).isEqualTo(ashaDealId);
            assertThat(lane.get("deal").get("title").asString()).startsWith("Asha");
        }
    }

    @Test
    void stepSummaryFallsBackToTheTypeWhenTheRuleIsChanged() throws Exception {
        try (AutomationDevice d = device()) {
            seed(d);
            mongo.updateFirst(new Query(Criteria.where("_id").is(welcome.getId())),
                    new Update().set("actions", List.of()), AutomationRule.class);
            JsonNode step = runs(welcome, "").get("items").get(0).get("steps").get(0);
            assertThat(step.get("summary").asString()).isEqualTo("Send template");
            assertThat(step.get("reason").asString()).isEqualTo("Sent “Welcome”");
        }
    }

    @Test
    void runsKeepFailureReasons() {
        String t = template("Welcome", "Hi {{contact.name}}");
        mongo.updateFirst(new Query(Criteria.where("_id").is(t)), new Update().set("status", TemplateStatus.ARCHIVED),
                MessageTemplate.class);
        AutomationRule broken = rule("Broken", AutomationEventKind.MESSAGE_RECEIVED, RuleStatus.PUBLISHED, null, null,
                send(t), new AutomationAction(AutomationActionType.ASSIGN_CHAT, null, null,
                        me.nageshk.omnichannel.db.automation.AssignMode.AGENT, "no-such-user", null,
                        me.nageshk.omnichannel.db.automation.WhenAway.KEEP_WITH_AGENT, null, null, null));
        incoming(n1, ASHA, "chat", "Hello", "Asha");
        worker.tick();

        JsonNode run = runs(broken, "").get("items").get(0);
        assertThat(run.get("status").asString()).isEqualTo("FAILED");
        JsonNode steps = run.get("steps");
        assertThat(steps).hasSize(2);
        assertThat(steps.get(0).get("outcome").asString()).isEqualTo("FAILED");
        assertThat(steps.get(0).get("reason").asString()).isEqualTo("The template is archived");
        assertThat(steps.get(1).get("outcome").asString()).isEqualTo("FAILED");
        assertThat(steps.get(1).get("reason").asString()).isEqualTo("The agent is no longer in the brand");
    }

    @Test
    void testRunsAreMarked() {
        AutomationRule draft = rule("Draft", AutomationEventKind.MESSAGE_RECEIVED, RuleStatus.DRAFT, null, null,
                addToCrm());
        Map<String, Object> body = new HashMap<>();
        body.put("contactId", null);
        body.put("phone", "919811100001");
        body.put("treatAsNew", false);
        assertThat(api(HttpMethod.POST, "/rules/" + draft.getId() + "/tests", manager, body).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        incoming(n1, ASHA, "chat", "Hello", "Asha");
        worker.tick();

        JsonNode page = runs(draft, "");
        assertThat(page.get("total").asInt()).isEqualTo(1);
        JsonNode run = page.get("items").get(0);
        assertThat(run.get("test").asBoolean()).isTrue();
        assertThat(run.get("status").asString()).isEqualTo("TESTING");
        assertThat(run.get("ruleName").asString()).isEqualTo("Draft");
        assertThat(run.get("steps")).isEmpty();
    }

    @Test
    void runsOfUnknownOrOtherBrandRuleAre404() {
        assertThat(api(HttpMethod.GET, "/rules/nope/runs", manager, null).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        AutomationRule r = rule("R", AutomationEventKind.MESSAGE_RECEIVED, RuleStatus.DRAFT, null, null);
        r.setWorkspaceId(other.getId());
        mongo.save(r);
        assertThat(api(HttpMethod.GET, "/rules/" + r.getId() + "/runs", manager, null).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        AutomationRule mine = rule("Mine", AutomationEventKind.MESSAGE_RECEIVED, RuleStatus.DRAFT, null, null);
        JsonNode empty = runs(mine, "");
        assertThat(empty.get("items")).isEmpty();
        assertThat(empty.get("total").asInt()).isZero();
    }

    @Test
    void eventsNewestFirstWithRuns() throws Exception {
        try (AutomationDevice d = device()) {
            seed(d);
            JsonNode page = events("");
            assertThat(page.get("total").asInt()).isEqualTo(4);
            assertThat(summaries(page)).hasSize(4);
            JsonNode items = page.get("items");
            assertThat(items.get(0).get("kind").asString()).isEqualTo("DEAL_LANE_CHANGED");
            assertThat(items.get(1).get("summary").asString()).isEqualTo("Message from Ravi: Hi");
            assertThat(items.get(2).get("summary").asString()).isEqualTo("Message from Asha: Again");
            JsonNode first = items.get(3);
            assertThat(first.get("summary").asString()).isEqualTo("Message from Asha: Hello");
            assertThat(first.get("kind").asString()).isEqualTo("MESSAGE_RECEIVED");
            assertThat(first.get("outcome").asString()).isEqualTo("DONE");
            assertThat(first.get("error").isNull()).isTrue();
            assertThat(first.get("test").asBoolean()).isFalse();
            assertThat(Instant.parse(first.get("createdAt").asString())).isEqualTo(t0);
            assertThat(Instant.parse(first.get("finishedAt").asString())).isEqualTo(t0);
            assertThat(first.get("contact").get("name").asString()).isEqualTo("Asha");
            assertThat(first.get("contact").get("id").asString()).isEqualTo(ashaContactId);
            assertThat(first.get("deal").isNull()).isTrue();
            assertThat(first.get("number").get("id").asString()).isEqualTo(n1.getId());
            assertThat(first.get("number").get("number").asString()).isEqualTo(n1.getNumber());
            List<String> names = new ArrayList<>();
            first.get("runs").forEach(r -> names.add(r.get("ruleName").asString()));
            assertThat(names).containsExactlyInAnyOrder("Welcome", "Leads");
            first.get("runs").forEach(r -> {
                assertThat(r.get("eventId").asString()).isEqualTo(first.get("id").asString());
                assertThat(r.get("steps")).hasSize(1);
            });
            assertThat(items.get(2).get("runs")).hasSize(1);
            assertThat(items.get(1).get("runs")).hasSize(1);
            assertThat(items.get(0).get("runs")).hasSize(1);
            assertThat(items.get(0).get("deal").get("id").asString()).isEqualTo(ashaDealId);
        }
    }

    @Test
    void filterByKind() throws Exception {
        try (AutomationDevice d = device()) {
            seed(d);
            JsonNode page = events("?kind=DEAL_LANE_CHANGED");
            assertThat(page.get("total").asInt()).isEqualTo(1);
            String title = page.get("items").get(0).get("deal").get("title").asString();
            String from = mongo.findAll(AutomationEventRecord.class).stream()
                    .filter(h -> h.getKind() == AutomationEventKind.DEAL_LANE_CHANGED).findFirst().orElseThrow()
                    .getData().fromLane().name();
            assertThat(page.get("items").get(0).get("summary").asString())
                    .isEqualTo(title + " moved from " + from + " to Won");
            assertThat(events("?kind=MESSAGE_RECEIVED").get("total").asInt()).isEqualTo(3);
        }
    }

    @Test
    void filterByRule() throws Exception {
        try (AutomationDevice d = device()) {
            seed(d);
            assertThat(summaries(events("?ruleId=" + welcome.getId()))).containsExactly("Message from Asha: Again",
                    "Message from Asha: Hello");
            assertThat(summaries(events("?ruleId=" + leads.getId()))).containsExactly("Message from Ravi: Hi",
                    "Message from Asha: Hello");
            assertThat(events("?ruleId=" + thanks.getId()).get("total").asInt()).isEqualTo(1);
            assertThat(events("?ruleId=nope").get("total").asInt()).isZero();
        }
    }

    @Test
    void filterByContact() throws Exception {
        try (AutomationDevice d = device()) {
            seed(d);
            assertThat(summaries(events("?contactId=" + ashaContactId))).hasSize(3)
                    .contains("Message from Asha: Hello", "Message from Asha: Again");
            assertThat(events("?contactId=nobody").get("total").asInt()).isZero();
        }
    }

    @Test
    void filterByDate() throws Exception {
        try (AutomationDevice d = device()) {
            seed(d);
            Instant second = t0.plus(Duration.ofHours(1));
            JsonNode page = events("?from=" + second.minusSeconds(60) + "&to=" + second.plusSeconds(60));
            assertThat(summaries(page)).containsExactly("Message from Asha: Again");
            assertThat(page.get("total").asInt()).isEqualTo(1);
            assertThat(events("?from=" + second).get("total").asInt()).isEqualTo(3);
            assertThat(events("?to=" + second).get("total").asInt()).isEqualTo(1);
        }
    }

    @Test
    void paging() {
        Instant base = clock.instant();
        for (int i = 0; i < 51; i++) record("e" + i, base.plusSeconds(i), EventOutcome.DONE, null);

        JsonNode first = events("");
        assertThat(first.get("total").asInt()).isEqualTo(51);
        assertThat(first.get("page").asInt()).isZero();
        assertThat(first.get("items")).hasSize(50);
        assertThat(first.get("items").get(0).get("id").asString()).isEqualTo("e50");
        JsonNode second = events("?page=1");
        assertThat(second.get("page").asInt()).isEqualTo(1);
        assertThat(second.get("total").asInt()).isEqualTo(51);
        assertThat(second.get("items")).hasSize(1);
        assertThat(second.get("items").get(0).get("id").asString()).isEqualTo("e0");
        assertThat(events("?page=2").get("items")).isEmpty();
        assertThat(new HashSet<>(summaries(first))).hasSize(1);
    }

    @Test
    void failedEventShowsError() {
        record("bad", clock.instant(), EventOutcome.FAILED, "rules unreadable");
        JsonNode item = events("").get("items").get(0);
        assertThat(item.get("outcome").asString()).isEqualTo("FAILED");
        assertThat(item.get("error").asString()).isEqualTo("rules unreadable");
        assertThat(item.get("runs")).isEmpty();
        assertThat(item.get("summary").asString()).isEqualTo("Message from Old: Old news");
    }

    @Test
    void historyIsKept() {
        record("old", clock.instant().minus(Duration.ofDays(730)), EventOutcome.DONE, null);
        JsonNode page = events("");
        assertThat(page.get("total").asInt()).isEqualTo(1);
        assertThat(page.get("items").get(0).get("id").asString()).isEqualTo("old");
    }

    @Test
    void otherBrandHistoryIsNotListed() {
        AutomationEventRecord r = record("foreign", clock.instant(), EventOutcome.DONE, null);
        r.setWorkspaceId(other.getId());
        mongo.save(r);
        assertThat(events("").get("total").asInt()).isZero();
    }

    @Test
    void viewOnlyRoleCanRead() {
        Role role = new Role();
        role.setName("AUTOMATION_VIEWER");
        role.setLevel(RoleLevel.BRAND);
        role.setAuthorities(new HashSet<>(List.of("automation:view")));
        role = roles.save(role);
        User viewer = testUsers.createInCustomer(acme.getId(), "viewer", null,
                List.of(new WorkspaceRole(brand.getId(), role.getId())));
        AutomationRule r = rule("R", AutomationEventKind.MESSAGE_RECEIVED, RuleStatus.DRAFT, null, null);
        assertThat(api(HttpMethod.GET, "/rules/" + r.getId() + "/runs", viewer, null).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(api(HttpMethod.GET, "/events", viewer, null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(api(HttpMethod.GET, "/events", agent, null).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }
}
