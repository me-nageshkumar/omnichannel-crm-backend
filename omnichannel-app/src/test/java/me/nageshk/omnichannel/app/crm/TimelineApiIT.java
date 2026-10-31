package me.nageshk.omnichannel.app.crm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.nageshk.omnichannel.db.access.Role;
import me.nageshk.omnichannel.db.access.RoleLevel;
import me.nageshk.omnichannel.db.access.RoleRepository;
import me.nageshk.omnichannel.db.contact.Contact;
import me.nageshk.omnichannel.db.contact.ContactNote;
import me.nageshk.omnichannel.db.user.User;
import me.nageshk.omnichannel.db.user.WorkspaceRole;
import me.nageshk.omnichannel.security.access.RoleCache;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import tools.jackson.databind.JsonNode;

class TimelineApiIT extends CrmTestBase {

    @Autowired RoleRepository roles;
    @Autowired RoleCache roleCache;

    String sales;

    @BeforeEach
    void enable() {
        enableCrm();
        sales = salesId();
        assignPipelines(manager2, sales);
    }

    private Map<String, Object> body(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private JsonNode timeline(String token, String type, String id) throws Exception {
        return json(send(get(crm() + "/activities?targetType=" + type + "&targetId=" + id), token, null)
                .andExpect(status().isOk()));
    }

    private Map<String, Object> log(String type, String id, String kind, String summary) {
        return body("target", body("type", type, "id", id), "type", kind, "summary", summary);
    }

    private List<String> types(JsonNode page) {
        return page.get("items").valueStream().map(i -> i.get("type").asText()).toList();
    }

    @Test
    void contactTimelineIncludesItsDealsAndLegacyNotes() throws Exception {
        Contact c = contact("Alice", "919811111111");
        JsonNode cap = captured(c, number);
        String crmId = cap.get("contact").get("id").asText();
        String dealId = cap.get("deal").get("id").asText();
        ContactNote n = new ContactNote();
        n.setWorkspaceId(brand.getId());
        n.setContactId(c.getId());
        n.setText("called earlier");
        n.setCreatedByName("Old Agent");
        mongo.insert(n);
        send(post(crm() + "/activities/log"), managerToken, log("DEAL", dealId, "CALL", "Spoke about pricing"))
                .andExpect(status().isOk());

        JsonNode page = timeline(managerToken, "CONTACT", crmId);
        assertThat(types(page)).contains("CALL_LOGGED", "RECORD_CREATED", "LEGACY_NOTE");
        // activities only: the contact's and the deal's RECORD_CREATED plus the call; the note is not counted
        assertThat(page.get("total").asLong()).isEqualTo(3);
        JsonNode note = page.get("items").valueStream().filter(i -> i.get("type").asText().equals("LEGACY_NOTE"))
                .findFirst().orElseThrow();
        assertThat(note.get("summary").asText()).isEqualTo("called earlier");
        assertThat(note.get("actor").get("name").asText()).isEqualTo("Old Agent");
        assertThat(note.get("target").get("id").asText()).isEqualTo(crmId);
        // newest first
        List<String> times = page.get("items").valueStream().map(i -> i.get("happenedAt").asText()).toList();
        assertThat(times).isSortedAccordingTo(java.util.Comparator.reverseOrder());
        // the deal's own timeline holds the call but not the contact's creation
        JsonNode deal = timeline(managerToken, "DEAL", dealId);
        assertThat(types(deal)).contains("CALL_LOGGED").doesNotContain("LEGACY_NOTE");
        // the company timeline is empty for a contact with no company
        JsonNode call = page.get("items").valueStream().filter(i -> i.get("type").asText().equals("CALL_LOGGED"))
                .findFirst().orElseThrow();
        assertThat(call.get("actor").get("id").asText()).isEqualTo(manager.getId());
        assertThat(call.get("actorType").asText()).isEqualTo("USER");
        // notes only on page 0
        JsonNode p1 = json(send(get(crm() + "/activities?targetType=CONTACT&targetId=" + crmId + "&page=1&size=1"),
                managerToken, null).andExpect(status().isOk()));
        assertThat(types(p1)).doesNotContain("LEGACY_NOTE");
    }

    @Test
    void companyTimelineIncludesItsDeals() throws Exception {
        Contact c = contact("Alice", "919811111111");
        String crmId = captured(c, number).get("contact").get("id").asText();
        String companyId = json(send(post(crm() + "/companies"), managerToken, body("name", "Globex"))
                .andExpect(status().is2xxSuccessful())).get("id").asText();
        String dealId = createDeal(body("title", "Big", "pipelineId", sales, "primaryCrmContactId", crmId,
                "companyId", companyId)).get("id").asText();
        send(post(crm() + "/activities/log"), managerToken, log("DEAL", dealId, "MEETING", "Kickoff"))
                .andExpect(status().isOk());
        send(post(crm() + "/activities/log"), managerToken, log("COMPANY", companyId, "EMAIL", "Intro mail"))
                .andExpect(status().isOk());
        JsonNode page = timeline(managerToken, "COMPANY", companyId);
        assertThat(types(page)).contains("MEETING_LOGGED", "EMAIL_LOGGED");
    }

    @Test
    void timelineHidesInaccessiblePipelines() throws Exception {
        Contact c = contact("Alice", "919811111111");
        String crmId = captured(c, number).get("contact").get("id").asText();
        String second = json(send(post(crm() + "/pipelines"), managerToken, Map.of("name", "Second"))
                .andExpect(status().is2xxSuccessful())).get("id").asText();
        String hiddenDeal = createDeal(body("title", "Hidden", "pipelineId", second, "primaryCrmContactId", crmId))
                .get("id").asText();
        send(post(crm() + "/activities/log"), managerToken, log("DEAL", hiddenDeal, "CALL", "Secret call"))
                .andExpect(status().isOk());
        send(post(crm() + "/activities/log"), managerToken, log("CONTACT", crmId, "CALL", "Open call"))
                .andExpect(status().isOk());
        assignPipelines(agent, sales);

        JsonNode asManager = timeline(managerToken, "CONTACT", crmId);
        assertThat(asManager.get("items").valueStream().map(i -> i.get("summary").asText()).toList())
                .contains("Secret call", "Open call");
        JsonNode asAgent = timeline(agentToken, "CONTACT", crmId);
        assertThat(asAgent.get("items").valueStream().map(i -> i.get("summary").asText()).toList())
                .contains("Open call").doesNotContain("Secret call");
        assertThat(asAgent.get("total").asLong()).isLessThan(asManager.get("total").asLong());
        send(get(crm() + "/activities?targetType=DEAL&targetId=" + hiddenDeal), agentToken, null)
                .andExpect(status().isNotFound());
        send(post(crm() + "/activities/log"), agentToken, log("DEAL", hiddenDeal, "CALL", "nope"))
                .andExpect(status().isNotFound());
    }

    private List<String> targetsSeenBy(String token, String crmContactId) throws Exception {
        return timeline(token, "CONTACT", crmContactId).get("items").valueStream()
                .filter(i -> i.get("target") != null && !i.get("target").isNull())
                .map(i -> i.get("target").get("id").asText()).toList();
    }

    private long version(String dealId) throws Exception {
        return json(send(get(crm() + "/deals/" + dealId), managerToken, null)).get("version").asLong();
    }

    @Test
    void entriesOfADealFollowItIntoAHiddenPipeline() throws Exception {
        // Alice stays visible to the Sales agent through her captured Sales deal
        String crmId = captured(contact("Alice", "919811111111"), number).get("contact").get("id").asText();
        String hidden = json(send(post(crm() + "/pipelines"), managerToken, Map.of("name", "Hidden"))
                .andExpect(status().is2xxSuccessful())).get("id").asText();
        String shared = json(send(post(crm() + "/pipelines"), managerToken, Map.of("name", "Shared"))
                .andExpect(status().is2xxSuccessful())).get("id").asText();
        String moved = createDeal(body("title", "Moves", "pipelineId", sales, "primaryCrmContactId", crmId))
                .get("id").asText();
        String relocated = createDeal(body("title", "Relocated", "pipelineId", shared, "primaryCrmContactId", crmId))
                .get("id").asText();
        send(put(crm() + "/deals/" + moved), managerToken, body("version", version(moved), "amount", 42))
                .andExpect(status().isOk());
        send(put(crm() + "/deals/" + relocated), managerToken, body("version", version(relocated), "amount", 7))
                .andExpect(status().isOk());
        assignPipelines(agent, sales, shared);
        assertThat(targetsSeenBy(agentToken, crmId)).contains(moved, relocated);

        // the manager moves one deal to the hidden pipeline and deletes the other's pipeline into it
        send(put(crm() + "/deals/" + moved), managerToken, body("version", version(moved), "pipelineId", hidden))
                .andExpect(status().isOk());
        send(delete(crm() + "/pipelines/" + shared + "?moveDealsToPipelineId=" + hidden), managerToken, null)
                .andExpect(status().isNoContent());
        List<String> seen = targetsSeenBy(agentToken, crmId);
        assertThat(seen).isNotEmpty().doesNotContain(moved, relocated);
        assertThat(targetsSeenBy(managerToken, crmId)).contains(moved, relocated);
        assertThat(mongo.count(new Query(Criteria.where("target.id").in(moved, relocated)
                .and("pipelineId").ne(hidden)), "crmActivities")).isZero();
    }

    @Test
    void loggingOnADealRaisesItsContactsLastActivity() throws Exception {
        String crmId = captured(contact("Alice", "919811111111"), number).get("contact").get("id").asText();
        String dealId = createDeal(body("title", "Big", "pipelineId", sales, "primaryCrmContactId", crmId))
                .get("id").asText();
        Instant before = Instant.parse(json(send(get(crm() + "/contacts/" + crmId), managerToken, null))
                .get("lastActivityAt").asText());
        Thread.sleep(5);
        JsonNode logged = json(send(post(crm() + "/activities/log"), managerToken,
                log("DEAL", dealId, "CALL", "Spoke")).andExpect(status().isOk()));
        Instant after = Instant.parse(json(send(get(crm() + "/contacts/" + crmId), managerToken, null))
                .get("lastActivityAt").asText());
        assertThat(after).isAfter(before);
        assertThat(mongo.findOne(new Query(Criteria.where("_id").is(logged.get("id").asText())), Document.class,
                "crmActivities").getList("relatedTargets", Document.class))
                .extracting(t -> t.getString("id")).contains(crmId);
    }

    @Test
    void logCallAppearsNewestFirst() throws Exception {
        Contact c = contact("Alice", "919811111111");
        String crmId = captured(c, number).get("contact").get("id").asText();
        Map<String, Object> earlier = log("CONTACT", crmId, "CALL", "Earlier");
        earlier.put("happenedAt", Instant.now().minusSeconds(3600).toString());
        send(post(crm() + "/activities/log"), managerToken, earlier).andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("CALL_LOGGED"));
        JsonNode latest = json(send(post(crm() + "/activities/log"), managerToken,
                log("CONTACT", crmId, "MEETING", "Latest")).andExpect(status().isOk()));
        assertThat(latest.get("type").asText()).isEqualTo("MEETING_LOGGED");
        JsonNode page = timeline(managerToken, "CONTACT", crmId);
        List<String> summaries = page.get("items").valueStream().map(i -> i.get("summary").asText()).toList();
        assertThat(summaries.get(0)).isEqualTo("Latest");
        assertThat(summaries.indexOf("Latest")).isLessThan(summaries.indexOf("Earlier"));
        // the channel number is recorded on the entry
        assertThat(mongo.findOne(new org.springframework.data.mongodb.core.query.Query(
                org.springframework.data.mongodb.core.query.Criteria.where("summary").is("Latest")),
                org.bson.Document.class, "crmActivities").getString("phoneNumberId")).isEqualTo(number.getId());
    }

    @Test
    void logInFutureRejected() throws Exception {
        String crmId = captured(contact("Alice", "919811111111"), number).get("contact").get("id").asText();
        Map<String, Object> m = log("CONTACT", crmId, "CALL", "Later");
        m.put("happenedAt", Instant.now().plusSeconds(600).toString());
        send(post(crm() + "/activities/log"), managerToken, m).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION")).andExpect(jsonPath("$.fields.happenedAt").exists());
    }

    @Test
    void logValidation() throws Exception {
        String crmId = captured(contact("Alice", "919811111111"), number).get("contact").get("id").asText();
        send(post(crm() + "/activities/log"), managerToken, log("CONTACT", crmId, "CALL", "x".repeat(2001)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION"))
                .andExpect(jsonPath("$.fields.summary").exists());
        send(post(crm() + "/activities/log"), managerToken, log("CONTACT", crmId, "CALL", "  "))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.summary").exists());
        send(post(crm() + "/activities/log"), managerToken, log("PIPELINE", sales, "CALL", "x"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields['target.type']").exists());
        send(post(crm() + "/activities/log"), managerToken, log("CONTACT", crmId, "FAX", "x"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.type").exists());
        send(post(crm() + "/activities/log"), managerToken, log("CONTACT", "missing", "CALL", "x"))
                .andExpect(status().isNotFound());
        send(get(crm() + "/activities?targetType=CONTACT&targetId=missing"), managerToken, null)
                .andExpect(status().isNotFound());
    }

    @Test
    void agentWithoutEditCannotLog() throws Exception {
        String crmId = captured(contact("Alice", "919811111111"), number).get("contact").get("id").asText();
        Role viewOnly = new Role();
        viewOnly.setName("CRM Viewer");
        viewOnly.setLevel(RoleLevel.BRAND);
        viewOnly.setAuthorities(new HashSet<>(Set.of("crm:view")));
        viewOnly = roles.save(viewOnly);
        roleCache.evictAll();
        User viewer = testUsers.createInCustomer(acme.getId(), "viewer", null,
                List.of(new WorkspaceRole(brand.getId(), viewOnly.getId())));
        String token = testUsers.accessToken(viewer);
        send(post(crm() + "/activities/log"), token, log("CONTACT", crmId, "CALL", "x"))
                .andExpect(status().isForbidden());
    }

    /** The deal's stored stageEnteredAt, as the stage-duration report will read it back. */
    private Instant enteredAt(String dealId) {
        Document d = mongo.findOne(new Query(Criteria.where("_id").is(new org.bson.types.ObjectId(dealId))),
                Document.class, "crmDeals");
        return ((java.util.Date) d.get("stageEnteredAt")).toInstant();
    }

    private List<Document> stageChanges(String dealId) {
        return mongo.find(new Query(Criteria.where("target.id").is(dealId).and("type").is("STAGE_CHANGED")),
                Document.class, "crmActivities");
    }

    @Test
    void stageChangeRecordsWhenTheStageWasEntered() throws Exception {
        Map<String, String> stages = stages(sales);
        JsonNode deal = captured(contact("Asha", "919900000701"), number).get("deal");
        String id = deal.get("id").asText();
        Instant beforeMove = enteredAt(id);
        send(post(crm() + "/deals/" + id + "/move"), managerToken, body("stageId", stages.get("Contacted")))
                .andExpect(status().isOk());
        List<Document> moves = stageChanges(id);
        assertThat(moves).hasSize(1);
        Document payload = moves.get(0).get("payload", Document.class);
        assertThat(((java.util.Date) payload.get("fromStageEnteredAt")).toInstant()).isEqualTo(beforeMove);

        // a pipeline change through an edit records it too
        String second = json(send(post(crm() + "/pipelines"), managerToken, body("name", "Second"))
                .andExpect(status().isCreated())).get("id").asText();
        Instant beforeEdit = enteredAt(id);
        JsonNode fresh = json(send(get(crm() + "/deals/" + id), managerToken, null));
        send(put(crm() + "/deals/" + id), managerToken,
                body("version", fresh.get("version").asLong(), "pipelineId", second)).andExpect(status().isOk());
        Document edit = stageChanges(id).get(1).get("payload", Document.class);
        assertThat(((java.util.Date) edit.get("fromStageEnteredAt")).toInstant()).isEqualTo(beforeEdit);
        assertThat(edit.getString("fromPipelineId")).isEqualTo(sales);

        // a stage removal with relocation records it on every relocated deal
        JsonNode other = captured(contact("Ravi", "919900000702"), number).get("deal");
        String otherId = other.get("id").asText();
        Instant beforeRemoval = enteredAt(otherId);
        JsonNode p = json(send(get(crm() + "/pipelines/" + sales), managerToken, null));
        List<Map<String, Object>> lanes = new java.util.ArrayList<>();
        for (JsonNode st : p.get("stages")) {
            Map<String, Object> m = body("id", st.get("id").asText(), "name", st.get("name").asText(),
                    "type", st.get("type").asText(), "probability", st.get("probability").asInt());
            if (st.get("name").asText().equals("New")) m.put("moveToStageId", stages.get("Contacted"));
            lanes.add(m);
        }
        send(put(crm() + "/pipelines/" + sales + "/stages"), managerToken,
                body("version", p.get("version").asLong(), "stages", lanes)).andExpect(status().isOk());
        Document relocated = stageChanges(otherId).get(0).get("payload", Document.class);
        assertThat(((java.util.Date) relocated.get("fromStageEnteredAt")).toInstant()).isEqualTo(beforeRemoval);
    }

    // --- redaction of moves through hidden pipelines (spec F C19, C21, C22) ----------------------------------

    private String pipeline(String name) throws Exception {
        return json(send(post(crm() + "/pipelines"), managerToken, Map.of("name", name))
                .andExpect(status().is2xxSuccessful())).get("id").asText();
    }

    private void relocate(String dealId, String pipelineId) throws Exception {
        send(put(crm() + "/deals/" + dealId), managerToken, body("version", version(dealId), "pipelineId", pipelineId))
                .andExpect(status().isOk());
    }

    private void moveTo(String dealId, String stageId) throws Exception {
        send(post(crm() + "/deals/" + dealId + "/move"), managerToken, body("stageId", stageId))
                .andExpect(status().isOk());
    }

    private List<String> moves(JsonNode page) {
        return page.get("items").valueStream().filter(i -> i.get("type").asText().equals("STAGE_CHANGED"))
                .map(i -> i.get("summary").asText()).toList();
    }

    /** A contact seen in Sales through its captured deal, and its second deal in a hidden pipeline. */
    private String[] dealInHiddenPipeline(String name, String phone, String hiddenName) throws Exception {
        String crmId = captured(contact(name, phone), number).get("contact").get("id").asText();
        String hidden = pipeline(hiddenName);
        String deal = createDeal(body("title", "Travels", "pipelineId", hidden, "primaryCrmContactId", crmId))
                .get("id").asText();
        assignPipelines(agent, sales);
        return new String[] {crmId, hidden, deal};
    }

    @Test
    void moveOutOfHiddenPipelineIsRedacted() throws Exception {
        String[] d = dealInHiddenPipeline("Asha", "919811111201", "Secret");
        relocate(d[2], sales);
        JsonNode mine = timeline(agentToken, "DEAL", d[2]);
        assertThat(moves(mine)).hasSize(1);
        assertThat(moves(mine).get(0)).endsWith("(" + "Sales" + ") from another pipeline").startsWith("Moved into ");
        assertThat(mine.toString()).doesNotContain("Secret").doesNotContain("fromStageName").doesNotContain(d[1]);
        assertThat(moves(timeline(managerToken, "DEAL", d[2])).get(0)).contains("Secret");
    }

    @Test
    void historyInsideAHiddenPipelineIsRedacted() throws Exception {
        String[] d = dealInHiddenPipeline("Ravi", "919811111202", "Secret");
        Map<String, String> hiddenStages = stages(d[1]);
        String next = hiddenStages.values().stream().skip(1).findFirst().orElseThrow();
        moveTo(d[2], next);
        String stageName = hiddenStages.entrySet().stream().filter(e -> e.getValue().equals(next)).findFirst()
                .orElseThrow().getKey();
        relocate(d[2], sales);
        JsonNode mine = timeline(agentToken, "DEAL", d[2]);
        assertThat(moves(mine)).hasSize(2);
        assertThat(moves(mine).get(1)).isEqualTo("Moved within another pipeline");
        assertThat(moves(mine).get(0)).endsWith("from another pipeline");
        assertThat(mine.toString()).doesNotContain("Secret").doesNotContain(next).doesNotContain(stageName);
        assertThat(moves(timeline(managerToken, "DEAL", d[2])).get(1)).contains(stageName);
    }

    @Test
    void moveThroughAHiddenPipelineIsRedacted() throws Exception {
        String[] d = dealInHiddenPipeline("Lata", "919811111203", "Secret");
        relocate(d[2], sales);
        relocate(d[2], d[1]);
        relocate(d[2], sales);
        List<String> mine = moves(timeline(agentToken, "DEAL", d[2]));
        assertThat(mine).hasSize(3);
        assertThat(mine.get(1)).startsWith("Moved from ").endsWith(" to another pipeline");
        assertThat(String.join(" ", mine)).doesNotContain("Secret");
    }

    @Test
    void contactAndCompanyTimelinesAreRedactedToo() throws Exception {
        String[] d = dealInHiddenPipeline("Tara", "919811111204", "Secret");
        String company = json(send(post(crm() + "/companies"), managerToken, Map.of("name", "Acme Ltd"))
                .andExpect(status().is2xxSuccessful())).get("id").asText();
        send(put(crm() + "/deals/" + d[2]), managerToken, body("version", version(d[2]), "companyId", company))
                .andExpect(status().isOk());
        relocate(d[2], sales);
        JsonNode contactSeen = timeline(agentToken, "CONTACT", d[0]);
        JsonNode companySeen = timeline(agentToken, "COMPANY", company);
        assertThat(moves(contactSeen)).isNotEmpty();
        assertThat(moves(companySeen)).isNotEmpty();
        assertThat(contactSeen.toString()).doesNotContain("Secret").doesNotContain(d[1]);
        assertThat(companySeen.toString()).doesNotContain("Secret").doesNotContain(d[1]);
    }

    @Test
    void visibleMoveStaysComplete() throws Exception {
        String[] d = dealInHiddenPipeline("Om", "919811111205", "Shared");
        assignPipelines(agent, sales, d[1]);
        relocate(d[2], sales);
        JsonNode mine = timeline(agentToken, "DEAL", d[2]);
        assertThat(moves(mine).get(0)).contains("Shared");
        assertThat(mine.toString()).contains("fromStageName");
    }

    @Test
    void stageRemovedFromAVisiblePipelineKeepsItsHistory() throws Exception {
        assignPipelines(agent, sales);
        String crmId = captured(contact("Ria", "919811111206"), number).get("contact").get("id").asText();
        String deal = createDeal(body("title", "Stays", "pipelineId", sales, "primaryCrmContactId", crmId))
                .get("id").asText();
        moveTo(deal, stages(sales).values().stream().skip(1).findFirst().orElseThrow());
        // the stage it left has since been removed: its id is in no pipeline any more
        mongo.updateFirst(new Query(Criteria.where("target.id").is(deal).and("type").is("STAGE_CHANGED")),
                new org.springframework.data.mongodb.core.query.Update().set("payload.fromStageId", "removed-stage"),
                "crmActivities");
        JsonNode mine = timeline(agentToken, "DEAL", deal);
        assertThat(mine.toString()).contains("fromStageName");
        assertThat(moves(mine).get(0)).startsWith("Moved from ").doesNotContain("another pipeline");
    }
}
