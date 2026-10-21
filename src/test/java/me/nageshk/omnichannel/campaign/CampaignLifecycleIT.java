package me.nageshk.omnichannel.campaign;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/** Pause, resume and terminate, and the transitions they refuse. */
class CampaignLifecycleIT extends CampaignTestBase {

    static final String P1 = "919811100001";
    static final String P2 = "919811100002";
    static final String P3 = "919811100003";

    void assertState(ResponseEntity<JsonNode> res, String message) {
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(res.getBody().get("code").asString()).isEqualTo("CAMPAIGN_STATE");
        assertThat(res.getBody().get("message").asString()).isEqualTo(message);
    }

    void setStatus(String id, CampaignStatus status) {
        mongo.updateFirst(new Query(Criteria.where("_id").is(id)), new Update().set("status", status), Campaign.class);
    }

    @Test
    void pauseStopsClaimsAndResumeContinues() throws Exception {
        try (AutoDevice d = device(n1)) {
            String id = startedFilterCampaign("Pausable", List.of(P1, P2), n1);
            ResponseEntity<JsonNode> paused = api(HttpMethod.POST, "/campaigns/" + id + "/pause", manager, null);
            assertThat(paused.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(paused.getBody().get("status").asString()).isEqualTo("PAUSED");
            assertThat(paused.getBody().get("pausedBy").asString()).isEqualTo("USER");
            assertThat(campaign(id).getPauseReason()).isNull();
            tickNow();
            assertThat(d.requests("sendMessage")).isEmpty();

            ResponseEntity<JsonNode> resumed = api(HttpMethod.POST, "/campaigns/" + id + "/resume", manager, null);
            assertThat(resumed.getBody().get("status").asString()).isEqualTo("RUNNING");
            assertThat(resumed.getBody().get("pausedBy").isNull()).isTrue();
            assertThat(campaign(id).getPausedBy()).isNull();
            tickNow();
            assertThat(d.requests("sendMessage")).hasSize(1);
        }
    }

    @Test
    void resumeAcknowledgesASystemPause() {
        String id = startedFilterCampaign("Logged out", List.of(P1), n1);
        mongo.updateFirst(new Query(Criteria.where("_id").is(id)), new Update().set("status", CampaignStatus.PAUSED)
                .set("pausedBy", PausedBy.SYSTEM).set("pauseReason", "TWO_LOGOUTS"), Campaign.class);
        ResponseEntity<JsonNode> res = api(HttpMethod.POST, "/campaigns/" + id + "/resume", manager, null);
        assertThat(res.getBody().get("status").asString()).isEqualTo("RUNNING");
        Campaign c = campaign(id);
        assertThat(c.getStatus()).isEqualTo(CampaignStatus.RUNNING);
        assertThat(c.getPausedBy()).isNull();
        assertThat(c.getPauseReason()).isNull();
    }

    @Test
    void terminateCancelsWhatIsPending() {
        String id = startedFilterCampaign("Stopped", List.of(P1, P2, P3, "919811100004"), n1);
        mongo.updateFirst(new Query(Criteria.where("campaignId").is(id).and("phone").is(P1)),
                new Update().set("status", RecipientStatus.SENDING).set("senderId", n1.getId()), CampaignRecipient.class);
        ResponseEntity<JsonNode> res = api(HttpMethod.POST, "/campaigns/" + id + "/terminate", manager, null);
        assertThat(res.getBody().get("status").asString()).isEqualTo("TERMINATED");
        assertThat(res.getBody().get("finishedAt").asString()).isEqualTo(WED_11_IST.toString());
        assertThat(recipient(id, P1).getStatus()).isEqualTo(RecipientStatus.SENDING);
        for (String p : List.of(P2, P3, "919811100004")) {
            assertThat(recipient(id, p).getStatus()).isEqualTo(RecipientStatus.SKIPPED);
            assertThat(recipient(id, p).getSkipReason()).isEqualTo(SkipReason.CANCELLED);
        }
        assertThat(res.getBody().at("/counts/skipped").asInt()).isEqualTo(3);
        assertThat(res.getBody().at("/counts/pending").asInt()).isEqualTo(1);
        assertThat(campaign(id).getFinishedAt()).isEqualTo(WED_11_IST);

        String paused = startedFilterCampaign("Paused then stopped", List.of(P1), n1);
        api(HttpMethod.POST, "/campaigns/" + paused + "/pause", manager, null);
        ResponseEntity<JsonNode> res2 = api(HttpMethod.POST, "/campaigns/" + paused + "/terminate", manager, null);
        assertThat(res2.getBody().get("status").asString()).isEqualTo("TERMINATED");
        assertThat(recipient(paused, P1).getSkipReason()).isEqualTo(SkipReason.CANCELLED);
    }

    @Test
    void illegalTransitionsAre409() {
        String draftFilter = filter("Everyone", java.util.Map.of());
        String t = templateNamed("Start Hi", "Hi {{contact.name}}");
        String draft = api(HttpMethod.POST, "/campaigns", manager, filterCampaign("Draft", draftFilter, List.of(t),
                List.of(), List.of(n1.getId()))).getBody().get("id").asString();
        String running = startedFilterCampaign("Running", List.of(P1), n1);
        String completed = startedFilterCampaign("Done", List.of(P1), n1);
        setStatus(completed, CampaignStatus.COMPLETED);
        assertState(api(HttpMethod.POST, "/campaigns/" + draft + "/pause", manager, null),
                "This campaign cannot be paused now.");
        assertState(api(HttpMethod.POST, "/campaigns/" + running + "/resume", manager, null),
                "This campaign cannot be resumed now.");
        assertState(api(HttpMethod.POST, "/campaigns/" + completed + "/terminate", manager, null),
                "This campaign cannot be terminated now.");
        assertState(api(HttpMethod.POST, "/campaigns/" + running + "/start", manager, null),
                "This campaign cannot be started now.");
    }

    @Test
    void lifecycleNeedsCampaignManage() {
        String id = startedFilterCampaign("Guarded", List.of(P1), n1);
        for (String verb : List.of("pause", "resume", "terminate")) {
            assertThat(api(HttpMethod.POST, "/campaigns/" + id + "/" + verb, agent, null).getStatusCode())
                    .as(verb).isEqualTo(HttpStatus.FORBIDDEN);
        }
        assertThat(campaign(id).getStatus()).isEqualTo(CampaignStatus.RUNNING);
        Campaign foreign = new Campaign();
        foreign.setWorkspaceId(other.getId());
        foreign.setCustomerId(acme.getId());
        foreign.setName("Elsewhere");
        foreign.setNameKey("elsewhere");
        foreign.setStatus(CampaignStatus.RUNNING);
        foreign = mongo.save(foreign);
        for (String verb : List.of("pause", "resume", "terminate")) {
            assertThat(api(HttpMethod.POST, "/campaigns/" + foreign.getId() + "/" + verb, manager, null)
                    .getStatusCode()).as(verb).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }
}
