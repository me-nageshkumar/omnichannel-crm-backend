package me.nageshk.omnichannel.app.crm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import me.nageshk.omnichannel.crm.access.CrmContext;
import me.nageshk.omnichannel.crm.access.PipelineAccess;
import me.nageshk.omnichannel.crm.activity.ActivityLog;
import me.nageshk.omnichannel.crm.activity.ActivityType;
import me.nageshk.omnichannel.crm.activity.CrmActivity;
import me.nageshk.omnichannel.crm.common.CrmTarget;
import me.nageshk.omnichannel.crm.common.TargetType;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

class ActivityLogIT extends CrmTestBase {

    @Autowired ActivityLog log;

    private CrmContext ctx() {
        return new CrmContext(brand.getId(), acme.getId(), manager.getId(), java.util.Set.of(), null, List.of(),
                java.util.Set.of(), null);
    }

    private void raw(String collection, String id) {
        mongo.insert(new Document("_id", id).append("workspaceId", brand.getId())
                .append("lastActivityAt", java.util.Date.from(Instant.parse("2026-01-01T00:00:00Z"))), collection);
    }

    private Instant lastActivity(String collection, String id) {
        Document d = mongo.findById(id, Document.class, collection);
        return d.getDate("lastActivityAt").toInstant();
    }

    @Test
    void recordUpdatesLastActivityOnTargetAndRelated() {
        raw("crmDeals", "d1");
        raw("crmContacts", "c1");
        raw("crmCompanies", "k1");
        Instant at = Instant.parse("2026-06-01T10:00:00Z");
        CrmActivity a = log.record(ctx(), ActivityType.CALL_LOGGED, new CrmTarget(TargetType.DEAL, "d1"),
                List.of(new CrmTarget(TargetType.CONTACT, "c1"), new CrmTarget(TargetType.COMPANY, "k1")),
                "p1", null, "Called", new HashMap<>(Map.of("happenedAt", at.toString())));
        assertThat(a.getId()).isNotNull();
        assertThat(a.getActorId()).isEqualTo(manager.getId());
        assertThat(a.getHappenedAt()).isEqualTo(at);
        assertThat(lastActivity("crmDeals", "d1")).isEqualTo(at);
        assertThat(lastActivity("crmContacts", "c1")).isEqualTo(at);
        assertThat(lastActivity("crmCompanies", "k1")).isEqualTo(at);
    }

    @Test
    void lastActivityNeverMovesBack() {
        raw("crmDeals", "d1");
        log.record(ctx(), ActivityType.SYSTEM, new CrmTarget(TargetType.DEAL, "d1"), List.of(), null, null, "old",
                new HashMap<>(Map.of("happenedAt", "2020-01-01T00:00:00Z")));
        assertThat(lastActivity("crmDeals", "d1")).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
    }

    @Test
    void settingsChangeIsRecorded() throws Exception {
        enableCrm();
        Map<String, Object> body = new HashMap<>(mapper.convertValue(
                json(send(get(crm() + "/settings"), managerToken, null).andExpect(status().isOk())),
                new tools.jackson.core.type.TypeReference<Map<String, Object>>() {}));
        body.put("dealRottingDays", 20);
        send(put(crm() + "/settings"), managerToken, body).andExpect(status().isOk());
        List<CrmActivity> all = mongo.find(new Query(Criteria.where("workspaceId").is(brand.getId())
                .and("type").is("SETTINGS_CHANGED")), CrmActivity.class);
        assertThat(all).hasSize(1);
        assertThat(all.get(0).getTarget().type()).isEqualTo(TargetType.SETTINGS);
        assertThat(all.get(0).getActorId()).isEqualTo(manager.getId());
    }

    @Test
    void pipelineAndAccessChangesAreRecorded() throws Exception {
        enableCrm();
        Map<String, Object> created = new HashMap<>(Map.of("name", "Partners"));
        String id = json(send(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                crm() + "/pipelines"), managerToken, created).andExpect(status().is2xxSuccessful())).get("id").asText();
        assignPipelines(agent, id);
        List<CrmActivity> all = mongo.find(new Query(Criteria.where("workspaceId").is(brand.getId())
                .and("type").is("SETTINGS_CHANGED")), CrmActivity.class);
        assertThat(all).extracting(CrmActivity::getSummary)
                .containsExactlyInAnyOrder("Created pipeline Partners", "Changed CRM pipelines of " + agent.getId());
        CrmActivity pipe = all.stream().filter(a -> a.getTarget().type() == TargetType.PIPELINE).findFirst().orElseThrow();
        assertThat(pipe.getTarget().id()).isEqualTo(id);
        assertThat(pipe.getPayload()).containsEntry("pipelineId", id);
    }
}
