package me.nageshk.omnichannel.app.crm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
}
