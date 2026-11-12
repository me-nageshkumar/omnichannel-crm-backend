package me.nageshk.omnichannel.app.automation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import me.nageshk.omnichannel.db.access.Role;
import me.nageshk.omnichannel.db.access.RoleLevel;
import me.nageshk.omnichannel.db.access.RoleRepository;
import me.nageshk.omnichannel.db.automation.AutomationAction;
import me.nageshk.omnichannel.db.automation.AutomationActionType;
import me.nageshk.omnichannel.db.automation.AutomationEventKind;
import me.nageshk.omnichannel.db.automation.AutomationRule;
import me.nageshk.omnichannel.db.automation.AutomationRun;
import me.nageshk.omnichannel.db.automation.AutomationTest;
import me.nageshk.omnichannel.db.automation.RuleStatus;
import me.nageshk.omnichannel.db.automation.RunStatus;
import me.nageshk.omnichannel.db.automation.TestEndReason;
import me.nageshk.omnichannel.db.automation.TestStatus;
import me.nageshk.omnichannel.db.template.MessageTemplate;
import me.nageshk.omnichannel.db.template.PlaceholderKind;
import me.nageshk.omnichannel.db.template.TemplateStatus;
import me.nageshk.omnichannel.db.template.TemplateType;
import me.nageshk.omnichannel.db.user.User;
import me.nageshk.omnichannel.db.user.WorkspaceRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

class AutomationRulesApiIT extends AutomationTestBase {

    @Autowired RoleRepository roles;

    private static Map<String, Object> send(String templateId) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("type", "SEND_TEMPLATE");
        a.put("templateId", templateId);
        a.put("values", Map.of());
        return a;
    }

    private static Map<String, Object> assign(String mode, String userId, String teamId) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("type", "ASSIGN_CHAT");
        a.put("mode", mode);
        a.put("userId", userId);
        a.put("teamId", teamId);
        return a;
    }

    private static Map<String, Object> crm(String pipelineId, String stageId) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("type", "ADD_TO_CRM");
        a.put("pipelineId", pipelineId);
        a.put("stageId", stageId);
        return a;
    }

    @SafeVarargs
    private static Map<String, Object> request(String name, Object conditions, Map<String, Object>... actions) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("name", name);
        r.put("description", null);
        r.put("event", "MESSAGE_RECEIVED");
        r.put("priority", null);
        r.put("conditions", conditions);
        r.put("actions", List.of(actions));
        return r;
    }

    private JsonNode create(Map<String, Object> body) {
        ResponseEntity<JsonNode> res = api(HttpMethod.POST, "/rules", manager, body);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return res.getBody();
    }

    private ResponseEntity<JsonNode> publishRule(String id) {
        return api(HttpMethod.POST, "/rules/" + id + "/publish", manager, null);
    }

    private String code(ResponseEntity<JsonNode> res) {
        return res.getBody().get("code").asString();
    }

    @Test
    void createStartsAsDraft() {
        JsonNode r = create(request("Welcome", null, send(template("Welcome", "Hi"))));
        assertThat(r.get("status").asString()).isEqualTo("DRAFT");
        assertThat(r.get("version").asLong()).isZero();
        assertThat(r.get("name").asString()).isEqualTo("Welcome");
        assertThat(r.get("event").asString()).isEqualTo("MESSAGE_RECEIVED");
        assertThat(r.get("createdBy").get("id").asString()).isEqualTo(manager.getId());
        assertThat(r.get("createdBy").get("name").asString()).isEqualTo(manager.getName());
        assertThat(r.get("publishedAt").isNull()).isTrue();
        assertThat(r.get("publishedBy").isNull()).isTrue();
        assertThat(r.get("activeTest").isNull()).isTrue();
        assertThat(r.get("actions")).hasSize(1);
        AutomationRule stored = mongo.findById(r.get("id").asString(), AutomationRule.class);
        assertThat(stored.getStatus()).isEqualTo(RuleStatus.DRAFT);
        assertThat(stored.getWorkspaceId()).isEqualTo(brand.getId());
        assertThat(stored.getCustomerId()).isEqualTo(acme.getId());
        assertThat(stored.getCreatedByUserId()).isEqualTo(manager.getId());
        assertThat(api(HttpMethod.GET, "/rules/" + stored.getId(), manager, null).getBody().get("name").asString())
                .isEqualTo("Welcome");
    }

    @Test
    void invalidShapeIs400() {
        ResponseEntity<JsonNode> res = api(HttpMethod.POST, "/rules", manager,
                request("Bad", all(leaf("nonsense", "is", "x")), send("t")));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(code(res)).isEqualTo("VALIDATION");
        assertThat(res.getBody().get("fields").get("conditions.rules[0].field").asString())
                .isEqualTo("is not a field of this event");
        assertThat(mongo.findAll(AutomationRule.class)).isEmpty();
    }

    @Test
    void publishedRuleCannotBeEdited() {
        String id = create(request("R", null, send(template("Welcome", "Hi")))).get("id").asString();
        assertThat(publishRule(id).getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = request("Renamed", null, send(template("Other", "Hi")));
        body.put("version", 1);
        ResponseEntity<JsonNode> res = api(HttpMethod.PUT, "/rules/" + id, manager, body);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(code(res)).isEqualTo("AUTOMATION_RULE_PUBLISHED");
        assertThat(res.getBody().get("message").asString()).isEqualTo("Move the rule to draft to change it.");
        assertThat(mongo.findById(id, AutomationRule.class).getName()).isEqualTo("R");
    }

    @Test
    void moveToDraftThenEdit() {
        String t = template("Welcome", "Hi");
        JsonNode created = create(request("R", null, send(t)));
        String id = created.get("id").asString();
        JsonNode published = publishRule(id).getBody();
        assertThat(published.get("status").asString()).isEqualTo("PUBLISHED");
        assertThat(published.get("publishedAt").isNull()).isFalse();
        assertThat(published.get("publishedBy").get("id").asString()).isEqualTo(manager.getId());
        assertThat(published.get("publishedBy").get("name").asString()).isEqualTo(manager.getName());

        ResponseEntity<JsonNode> un = api(HttpMethod.POST, "/rules/" + id + "/unpublish", manager, null);
        assertThat(un.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(un.getBody().get("status").asString()).isEqualTo("DRAFT");
        assertThat(un.getBody().get("publishedAt").isNull()).isTrue();
        long version = un.getBody().get("version").asLong();

        Map<String, Object> body = request("Renamed", all(leaf("message.text", "contains", "hi")), send(t));
        body.put("version", version);
        ResponseEntity<JsonNode> put = api(HttpMethod.PUT, "/rules/" + id, manager, body);
        assertThat(put.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(put.getBody().get("name").asString()).isEqualTo("Renamed");
        assertThat(put.getBody().get("version").asLong()).isGreaterThan(version);
        assertThat(put.getBody().get("conditions").get("rules").get(0).get("field").asString())
                .isEqualTo("message.text");
        assertThat(publishRule(id).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(mongo.findById(id, AutomationRule.class).getName()).isEqualTo("Renamed");
    }

    @Test
    void updateWithoutVersionIs400() {
        String id = create(request("R", null, send("t"))).get("id").asString();
        ResponseEntity<JsonNode> res = api(HttpMethod.PUT, "/rules/" + id, manager, request("R2", null, send("t")));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().get("fields").get("version").asString()).isEqualTo("is required");
    }

    @Test
    void publishChecksWholeness() {
        // no actions
        String none = create(request("None", null)).get("id").asString();
        ResponseEntity<JsonNode> res = publishRule(none);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(code(res)).isEqualTo("VALIDATION");
        assertThat(res.getBody().get("fields").get("actions").asString()).isEqualTo("Add at least one action");
        assertThat(mongo.findById(none, AutomationRule.class).getStatus()).isEqualTo(RuleStatus.DRAFT);

        // archived template, missing, contact card, no template
        String archived = template("Old", "Hi");
        MessageTemplate old = mongo.findById(archived, MessageTemplate.class);
        old.setStatus(TemplateStatus.ARCHIVED);
        mongo.save(old);
        String card = template("Card", "Hi");
        MessageTemplate c = mongo.findById(card, MessageTemplate.class);
        c.setType(TemplateType.CONTACT_CARD);
        mongo.save(c);
        Map<String, Object> noTemplate = send(null);
        String templates = create(request("Templates", null, send(archived), send("gone"), send(card), noTemplate))
                .get("id").asString();
        JsonNode f = publishRule(templates).getBody().get("fields");
        assertThat(f.get("actions[0].templateId").asString()).isEqualTo("The template is archived");
        assertThat(f.get("actions[1].templateId").asString()).isEqualTo("The template no longer exists");
        assertThat(f.get("actions[2].templateId").asString()).isEqualTo("Contact card templates cannot be sent");
        assertThat(f.get("actions[3].templateId").asString()).isEqualTo("Choose a template");

        // required agent placeholder without a value
        String ordered = template("Order", "Order {{order_id}} {{note}}");
        MessageTemplate o = mongo.findById(ordered, MessageTemplate.class);
        o.getPlaceholders().forEach(p -> {
            p.setKind(PlaceholderKind.AGENT);
            p.setRequired(p.getName().equals("order_id"));
        });
        mongo.save(o);
        String values = create(request("Values", null, send(ordered))).get("id").asString();
        f = publishRule(values).getBody().get("fields");
        assertThat(f.get("actions[0].values.order_id").asString()).isEqualTo("order_id needs a value");
        assertThat(f.has("actions[0].values.note")).isFalse();

        // agent and team
        String people = create(request("People", null, assign("AGENT", null, null),
                assign("AGENT", "gone", null), assign("AGENT", outsider.getId(), null),
                assign("TEAM_QUEUE", null, null), assign("TEAM_ROUND_ROBIN", null, "gone"),
                assign("TEAM_QUEUE", null, support.getId()))).get("id").asString();
        f = publishRule(people).getBody().get("fields");
        assertThat(f.get("actions[0].userId").asString()).isEqualTo("Choose an agent");
        assertThat(f.get("actions[1].userId").asString()).isEqualTo("The agent is no longer in the brand");
        assertThat(f.has("actions[2].userId")).isFalse();
        assertThat(f.get("actions[3].teamId").asString()).isEqualTo("Choose a team");
        assertThat(f.get("actions[4].teamId").asString()).isEqualTo("The team no longer exists");
        assertThat(f.has("actions[5].teamId")).isFalse();

        // CRM off
        String off = create(request("Off", null, crm(null, null))).get("id").asString();
        f = publishRule(off).getBody().get("fields");
        assertThat(f.get("actions[0].pipelineId").asString()).isEqualTo("CRM is off for this brand");

        // CRM on: pipeline, lane
        enableCrm();
        String pipelineId = api(HttpMethod.GET, "/options", manager, null).getBody().get("crm")
                .get("defaultPipelineId").asString();
        String on = create(request("On", null, crm("gone", null), crm(pipelineId, "gone"), crm(null, null),
                crm(pipelineId, null))).get("id").asString();
        f = publishRule(on).getBody().get("fields");
        assertThat(f.get("actions[0].pipelineId").asString()).isEqualTo("The pipeline no longer exists");
        assertThat(f.get("actions[1].stageId").asString()).isEqualTo("The lane is not in the pipeline");
        assertThat(f.has("actions[2].pipelineId")).isFalse();
        assertThat(f.has("actions[3].stageId")).isFalse();
        assertThat(mongo.findById(on, AutomationRule.class).getStatus()).isEqualTo(RuleStatus.DRAFT);
    }

    @Test
    void publishRejectsShapeErrorsOfAStoredRule() {
        AutomationRule r = rule("Stored", AutomationEventKind.MESSAGE_RECEIVED, RuleStatus.DRAFT, null,
                all(leaf("nonsense", "is", "x")),
                new AutomationAction(AutomationActionType.SEND_TEMPLATE, template("W", "Hi"), null, null, null, null,
                        null, null, null, null, null, null));
        ResponseEntity<JsonNode> res = publishRule(r.getId());
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().get("fields").get("conditions.rules[0].field").asString())
                .isEqualTo("is not a field of this event");
    }

    @Test
    void publishTwiceIsUnchanged() {
        String id = create(request("R", null, send(template("Welcome", "Hi")))).get("id").asString();
        JsonNode first = publishRule(id).getBody();
        clock.advance(Duration.ofHours(1));
        ResponseEntity<JsonNode> second = publishRule(id);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getBody().get("publishedAt")).isEqualTo(first.get("publishedAt"));
        assertThat(second.getBody().get("version")).isEqualTo(first.get("version"));
        // unpublish of a draft is also unchanged
        String draft = create(request("D", null)).get("id").asString();
        ResponseEntity<JsonNode> un = api(HttpMethod.POST, "/rules/" + draft + "/unpublish", manager, null);
        assertThat(un.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(un.getBody().get("status").asString()).isEqualTo("DRAFT");
    }

    @Test
    void deleteDraftOnly() {
        String id = create(request("R", null, send(template("Welcome", "Hi")))).get("id").asString();
        publishRule(id);
        ResponseEntity<JsonNode> res = api(HttpMethod.DELETE, "/rules/" + id, manager, null);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(code(res)).isEqualTo("AUTOMATION_RULE_PUBLISHED");
        assertThat(mongo.findById(id, AutomationRule.class)).isNotNull();

        api(HttpMethod.POST, "/rules/" + id + "/unpublish", manager, null);
        assertThat(api(HttpMethod.DELETE, "/rules/" + id, manager, null).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(mongo.findById(id, AutomationRule.class)).isNull();
        ResponseEntity<JsonNode> gone = api(HttpMethod.GET, "/rules/" + id, manager, null);
        assertThat(gone.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(code(gone)).isEqualTo("NOT_FOUND");
    }

    @Test
    void staleVersionIs409() {
        JsonNode r = create(request("R", null, send("t")));
        String id = r.get("id").asString();
        Map<String, Object> body = request("R2", null, send("t"));
        body.put("version", 0);
        assertThat(api(HttpMethod.PUT, "/rules/" + id, manager, body).getStatusCode()).isEqualTo(HttpStatus.OK);
        body.put("name", "R3");
        ResponseEntity<JsonNode> stale = api(HttpMethod.PUT, "/rules/" + id, manager, body);
        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(code(stale)).isEqualTo("CONFLICT");
        assertThat(mongo.findById(id, AutomationRule.class).getName()).isEqualTo("R2");
    }

    @Test
    void publishAndDeleteEndTheActiveTest() {
        String t = template("Welcome", "Hi");
        String id = create(request("R", null, send(t))).get("id").asString();
        AutomationTest test = waitingTest(AutomationEventKind.MESSAGE_RECEIVED, null, "919800000001");
        test.setRuleId(id);
        test.setStartedByUserId(manager.getId());
        test.setStartedByName(manager.getName());
        mongo.save(test);
        JsonNode view = api(HttpMethod.GET, "/rules/" + id, manager, null).getBody();
        assertThat(view.get("activeTest").get("id").asString()).isEqualTo(test.getId());
        assertThat(view.get("activeTest").get("startedBy").get("id").asString()).isEqualTo(manager.getId());
        assertThat(view.get("activeTest").get("startedBy").get("name").asString()).isEqualTo(manager.getName());
        assertThat(view.get("activeTest").get("status").asString()).isEqualTo("WAITING");

        JsonNode published = publishRule(id).getBody();
        assertThat(published.get("activeTest").isNull()).isTrue();
        AutomationTest ended = mongo.findById(test.getId(), AutomationTest.class);
        assertThat(ended.isActive()).isFalse();
        assertThat(ended.getStatus()).isEqualTo(TestStatus.ENDED);
        assertThat(ended.getEndReason()).isEqualTo(TestEndReason.RULE_PUBLISHED);

        // a second test on the draft ends when the rule is deleted
        api(HttpMethod.POST, "/rules/" + id + "/unpublish", manager, null);
        AutomationTest second = waitingTest(AutomationEventKind.MESSAGE_RECEIVED, null, "919800000002");
        second.setRuleId(id);
        mongo.save(second);
        assertThat(api(HttpMethod.DELETE, "/rules/" + id, manager, null).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        AutomationTest deleted = mongo.findById(second.getId(), AutomationTest.class);
        assertThat(deleted.isActive()).isFalse();
        assertThat(deleted.getStatus()).isEqualTo(TestStatus.ENDED);
        assertThat(deleted.getEndReason()).isEqualTo(TestEndReason.RULE_DELETED);
    }

    @Test
    void changingTheEventWhileTestingIsRefused() {
        String t = template("Welcome", "Hi");
        String id = create(request("R", null, send(t))).get("id").asString();
        AutomationTest test = waitingTest(AutomationEventKind.MESSAGE_RECEIVED, null, "919800000001");
        test.setRuleId(id);
        mongo.save(test);

        Map<String, Object> body = request("R", null, send(t));
        body.put("event", "DEAL_LANE_CHANGED");
        body.put("version", 0);
        ResponseEntity<JsonNode> res = api(HttpMethod.PUT, "/rules/" + id, manager, body);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(code(res)).isEqualTo("VALIDATION");
        assertThat(res.getBody().get("fields").get("event").asString())
                .isEqualTo("End the running test before changing the event");
        assertThat(mongo.findById(id, AutomationRule.class).getEvent())
                .isEqualTo(AutomationEventKind.MESSAGE_RECEIVED);
        assertThat(mongo.findById(test.getId(), AutomationTest.class).isActive()).isTrue();

        // other changes go through while the test runs
        Map<String, Object> rename = request("Renamed", null, send(t));
        rename.put("version", 0);
        assertThat(api(HttpMethod.PUT, "/rules/" + id, manager, rename).getStatusCode()).isEqualTo(HttpStatus.OK);

        // with no test running the event can change
        mongo.updateFirst(new org.springframework.data.mongodb.core.query.Query(
                org.springframework.data.mongodb.core.query.Criteria.where("_id").is(test.getId())),
                new org.springframework.data.mongodb.core.query.Update().set("active", false), AutomationTest.class);
        body.put("version", 1);
        assertThat(api(HttpMethod.PUT, "/rules/" + id, manager, body).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(mongo.findById(id, AutomationRule.class).getEvent())
                .isEqualTo(AutomationEventKind.DEAL_LANE_CHANGED);
    }

    private void run(String ruleId, String eventId, boolean test, RunStatus status, java.time.Instant at) {
        AutomationRun r = new AutomationRun();
        r.setCustomerId(acme.getId());
        r.setWorkspaceId(brand.getId());
        r.setRuleId(ruleId);
        r.setEventId(eventId);
        r.setTest(test);
        r.setStatus(status);
        r.setStartedAt(at);
        mongo.save(r);
    }

    @Test
    void listInRunOrderWithStats() {
        String welcome = template("Welcome", "Hi");
        AutomationRule b = rule("B", AutomationEventKind.MESSAGE_RECEIVED, RuleStatus.PUBLISHED, 5, null,
                new AutomationAction(AutomationActionType.SEND_TEMPLATE, welcome, null, null, null, null, null, null,
                        null, null, null, null),
                new AutomationAction(AutomationActionType.ASSIGN_CHAT, null, null,
                        me.nageshk.omnichannel.db.automation.AssignMode.TEAM_ROUND_ROBIN, null, support.getId(), null,
                        null, null, null, null, null),
                new AutomationAction(AutomationActionType.ADD_TO_CRM, null, null, null, null, null, null, null, null,
                        null, null, null));
        AutomationRule a = rule("A", AutomationEventKind.MESSAGE_RECEIVED, RuleStatus.DRAFT, null, null);
        AutomationRule c = rule("C", AutomationEventKind.DEAL_LANE_CHANGED, RuleStatus.PUBLISHED, 10, null);
        run(b.getId(), "e1", false, RunStatus.FAILED, NOW.minus(Duration.ofDays(2)));
        run(b.getId(), "e2", false, RunStatus.DONE, NOW.minus(Duration.ofDays(8)));
        run(b.getId(), "e3", true, RunStatus.DONE, NOW.minus(Duration.ofHours(1)));

        ResponseEntity<JsonNode> res = api(HttpMethod.GET, "/rules", manager, null);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode list = res.getBody();
        assertThat(list).hasSize(3);
        assertThat(list.get(0).get("name").asString()).isEqualTo("C");
        assertThat(list.get(1).get("name").asString()).isEqualTo("B");
        assertThat(list.get(2).get("name").asString()).isEqualTo("A");
        JsonNode row = list.get(1);
        assertThat(row.get("id").asString()).isEqualTo(b.getId());
        assertThat(row.get("event").asString()).isEqualTo("MESSAGE_RECEIVED");
        assertThat(row.get("status").asString()).isEqualTo("PUBLISHED");
        assertThat(row.get("priority").asInt()).isEqualTo(5);
        assertThat(row.get("runs7d").asLong()).isEqualTo(1);
        assertThat(row.get("failures7d").asLong()).isEqualTo(1);
        assertThat(java.time.Instant.parse(row.get("lastRunAt").asString()))
                .isEqualTo(NOW.minus(Duration.ofDays(2)));
        assertThat(row.get("creationTime").isNull()).isFalse();
        assertThat(row.get("actionsSummary").asString())
                .isEqualTo("Send “Welcome” · Round robin in support · Add to CRM (default pipeline)");
        assertThat(list.get(0).get("runs7d").asLong()).isZero();
        assertThat(list.get(0).get("lastRunAt").isNull()).isTrue();
        assertThat(list.get(0).get("priority").asInt()).isEqualTo(10);
        assertThat(list.get(2).get("priority").isNull()).isTrue();
        assertThat(list.get(2).get("actionsSummary").asString()).isEmpty();
    }

    @Test
    void summaryNamesAgentsAndShowsIdsOfMissingThings() {
        AutomationRule r = rule("S", AutomationEventKind.MESSAGE_RECEIVED, RuleStatus.DRAFT, null, null,
                new AutomationAction(AutomationActionType.ASSIGN_CHAT, null, null,
                        me.nageshk.omnichannel.db.automation.AssignMode.AGENT, agent.getId(), null, null, null, null,
                        null, null, null),
                new AutomationAction(AutomationActionType.ASSIGN_CHAT, null, null,
                        me.nageshk.omnichannel.db.automation.AssignMode.TEAM_QUEUE, null, support.getId(), null, null,
                        null, null, null, null),
                new AutomationAction(AutomationActionType.SEND_TEMPLATE, "gone", null, null, null, null, null, null,
                        null, null, null, null));
        String summary = api(HttpMethod.GET, "/rules", manager, null).getBody().get(0).get("actionsSummary")
                .asString();
        assertThat(summary).isEqualTo("Assign to " + agent.getName() + " · Queue in support · Send “gone”");
        assertThat(r.getId()).isNotNull();
    }

    @Test
    void optionsListTheBrand() {
        String welcome = template("Welcome", "Hi {{contact.name}} {{order_id}}");
        String archived = template("Old", "Hi");
        MessageTemplate old = mongo.findById(archived, MessageTemplate.class);
        old.setStatus(TemplateStatus.ARCHIVED);
        mongo.save(old);
        String card = template("Card", "Hi");
        MessageTemplate cc = mongo.findById(card, MessageTemplate.class);
        cc.setType(TemplateType.CONTACT_CARD);
        mongo.save(cc);

        ResponseEntity<JsonNode> res = api(HttpMethod.GET, "/options", manager, null);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode o = res.getBody();

        assertThat(o.get("numbers")).hasSize(2);
        JsonNode first = null;
        JsonNode second = null;
        for (JsonNode n : o.get("numbers")) {
            if (n.get("id").asString().equals(n1.getId())) first = n;
            else if (n.get("id").asString().equals(n2.getId())) second = n;
        }
        assertThat(first.get("number").asString()).isEqualTo(n1.getNumber());
        assertThat(first.get("owningTeam").get("id").asString()).isEqualTo(support.getId());
        assertThat(first.get("owningTeam").get("name").asString()).isEqualTo("support");
        assertThat(second.get("owningTeam").isNull()).isTrue();

        assertThat(o.get("teams")).hasSize(1);
        assertThat(o.get("teams").get(0).get("id").asString()).isEqualTo(support.getId());

        Map<String, JsonNode> agents = new LinkedHashMap<>();
        for (JsonNode a : o.get("agents")) agents.put(a.get("id").asString(), a);
        assertThat(agents).containsKeys(manager.getId(), agent.getId(), agent2.getId(), outsider.getId());
        assertThat(agents).containsKey(admin.getId()).doesNotContainKey(staff.getId());
        assertThat(agents.get(admin.getId()).get("seesAllNumbers").asBoolean()).isTrue();
        assertThat(agents.get(manager.getId()).get("seesAllNumbers").asBoolean()).isTrue();
        assertThat(agents.get(agent.getId()).get("seesAllNumbers").asBoolean()).isFalse();
        assertThat(agents.get(agent.getId()).get("name").asString()).isEqualTo(agent.getName());
        List<String> teamIds = new ArrayList<>();
        agents.get(agent.getId()).get("teamIds").forEach(t -> teamIds.add(t.asString()));
        assertThat(teamIds).containsExactly(support.getId());
        assertThat(agents.get(manager.getId()).get("teamIds")).isEmpty();

        assertThat(o.get("templates")).hasSize(1);
        JsonNode t = o.get("templates").get(0);
        assertThat(t.get("id").asString()).isEqualTo(welcome);
        assertThat(t.get("name").asString()).isEqualTo("Welcome");
        assertThat(t.get("type").asString()).isEqualTo("TEXT");
        assertThat(t.get("placeholders")).hasSize(2);
        JsonNode contact = t.get("placeholders").get(0);
        assertThat(contact.get("name").asString()).isEqualTo("contact.name");
        assertThat(contact.get("kind").asString()).isEqualTo("CONTACT_FIELD");
        assertThat(contact.get("required").asBoolean()).isFalse();
        assertThat(contact.get("fallback").asString()).isEqualTo("there");
        assertThat(t.get("placeholders").get(1).get("label").asString()).isEqualTo("order_id");

        assertThat(o.get("crm").get("enabled").asBoolean()).isFalse();
        assertThat(o.get("crm").get("pipelines")).isEmpty();
        assertThat(o.get("crm").get("defaultPipelineId").isNull()).isTrue();

        enableCrm();
        JsonNode crm = api(HttpMethod.GET, "/options", manager, null).getBody().get("crm");
        assertThat(crm.get("enabled").asBoolean()).isTrue();
        assertThat(crm.get("pipelines")).hasSize(1);
        JsonNode p = crm.get("pipelines").get(0);
        assertThat(p.get("id").asString()).isEqualTo(crm.get("defaultPipelineId").asString());
        assertThat(p.get("active").asBoolean()).isTrue();
        assertThat(p.get("lanes").size()).isGreaterThan(1);
        assertThat(p.get("lanes").get(0).get("type").asString()).isEqualTo("OPEN");
        assertThat(p.get("lanes").get(0).get("name").asString()).isNotBlank();
    }

    @Test
    void optionsAndPublishWorkForACrmBrandNeverOpened() {
        enableCrm();
        JsonNode crm = api(HttpMethod.GET, "/options", manager, null).getBody().get("crm");
        assertThat(crm.get("defaultPipelineId").isNull()).isFalse();
        assertThat(crm.get("pipelines").get(0).get("id").asString()).isEqualTo(crm.get("defaultPipelineId").asString());
        assertThat(crm.get("pipelines").get(0).get("lanes").size()).isGreaterThan(0);
        String id = create(request("Lead", null, crm(null, null))).get("id").asString();
        assertThat(publishRule(id).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void agentIsForbidden() {
        assertThat(api(HttpMethod.GET, "/rules", agent, null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(api(HttpMethod.GET, "/options", agent, null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(api(HttpMethod.POST, "/rules", agent, request("R", null, send("t"))).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(mongo.findAll(AutomationRule.class)).isEmpty();
    }

    @Test
    void customRoleWithViewOnly() {
        Role role = new Role();
        role.setName("AUTOMATION_VIEWER");
        role.setLevel(RoleLevel.BRAND);
        role.setAuthorities(new java.util.HashSet<>(List.of("automation:view")));
        role = roles.save(role);
        User viewer = testUsers.createInCustomer(acme.getId(), "viewer", null,
                List.of(new WorkspaceRole(brand.getId(), role.getId())));
        AutomationRule r = rule("R", AutomationEventKind.MESSAGE_RECEIVED, RuleStatus.DRAFT, null, null);
        assertThat(api(HttpMethod.GET, "/rules", viewer, null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(api(HttpMethod.GET, "/options", viewer, null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(api(HttpMethod.GET, "/rules/" + r.getId(), viewer, null).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(api(HttpMethod.POST, "/rules", viewer, request("R", null, send("t"))).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(api(HttpMethod.POST, "/rules/" + r.getId() + "/publish", viewer, null).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(api(HttpMethod.DELETE, "/rules/" + r.getId(), viewer, null).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void otherBrandRuleIs404() {
        AutomationRule r = rule("R", AutomationEventKind.MESSAGE_RECEIVED, RuleStatus.DRAFT, null, null);
        r.setWorkspaceId(other.getId());
        mongo.save(r);
        assertThat(api(HttpMethod.GET, "/rules/" + r.getId(), manager, null).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(api(HttpMethod.POST, "/rules/" + r.getId() + "/publish", manager, null).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(api(HttpMethod.DELETE, "/rules/" + r.getId(), manager, null).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        Map<String, Object> body = request("R2", null);
        body.put("version", 0);
        assertThat(api(HttpMethod.PUT, "/rules/" + r.getId(), manager, body).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(api(HttpMethod.GET, "/rules", manager, null).getBody()).isEmpty();
        assertThat(mongo.findById(r.getId(), AutomationRule.class)).isNotNull();
    }
}
