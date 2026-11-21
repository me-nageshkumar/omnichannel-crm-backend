package me.nageshk.omnichannel.app.meta;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import me.nageshk.omnichannel.db.campaign.Campaign;
import me.nageshk.omnichannel.db.campaign.CampaignStatus;
import me.nageshk.omnichannel.db.campaign.PausedBy;
import me.nageshk.omnichannel.db.campaign.RecipientStatus;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/** A Cloud campaign pauses itself when Meta stops the template, the number or the token (spec 2026-11-21 §5.5). */
class CloudCampaignPausesIT extends CloudCampaignTestBase {

    private String template;

    private String running(String name, int recipients) {
        template = syncTemplate(FOLLOW_UP);
        List<String> phones = new java.util.ArrayList<>();
        for (int i = 0; i < recipients; i++) phones.add("9197000000" + (10 + i));
        String id = cloudCampaign(name, template, phones, nameIntoBody1(), numberId);
        start(id);
        return id;
    }

    private void templateStatus(String event) throws InterruptedException {
        webhook(webhookKey, envelope("{\"field\":\"message_template_status_update\",\"value\":{\"event\":\"" + event
                + "\",\"message_template_id\":\"t1\",\"message_template_name\":\"course_follow_up\","
                + "\"message_template_language\":\"en\"}}"));
        await(() -> event.equals(mongo.findById(template, org.bson.Document.class, "cloudTemplates").getString("status")));
    }

    private Campaign campaign(String id) {
        return mongo.findById(id, Campaign.class);
    }

    private ResponseEntity<JsonNode> resume(String id) {
        return ws(HttpMethod.POST, "/campaigns/" + id + "/resume", manager, null);
    }

    @Test
    void pausedTemplatePausesTheCampaign() throws Exception {
        String id = running("Spring intake", 2);
        templateStatus("PAUSED");
        tickNow();
        Campaign c = campaign(id);
        assertThat(c.getStatus()).isEqualTo(CampaignStatus.PAUSED);
        assertThat(c.getPausedBy()).isEqualTo(PausedBy.SYSTEM);
        assertThat(c.getPauseReason()).isEqualTo("TEMPLATE_UNAVAILABLE");
        assertThat(GRAPH.recorded("POST /333/messages")).isEmpty();
    }

    @Test
    void redQualityPausesTheCampaign() throws Exception {
        String id = running("Red intake", 2);
        mongo.updateFirst(new Query(Criteria.where("phoneNumberId").is(numberId)),
                new Update().set("qualityRating", "RED"), "cloudAccounts");
        tickNow();
        assertThat(campaign(id).getPauseReason()).isEqualTo("CLOUD_QUALITY_LOW");
        assertThat(GRAPH.recorded("POST /333/messages")).isEmpty();
    }

    @Test
    void revokedTokenPausesWithCloudAuth() throws Exception {
        String id = running("Token intake", 2);
        GRAPH.enqueue("POST /333/messages", 401, "{\"error\":{\"message\":\"Error validating access token\","
                + "\"type\":\"OAuthException\",\"code\":190}}");
        tickNow();
        assertThat(campaign(id).getPauseReason()).isEqualTo("CLOUD_AUTH");
        assertThat(recipients(id)).allSatisfy(r -> assertThat(r.getStatus()).isEqualTo(RecipientStatus.PENDING));
    }

    @Test
    void resumeRefusedWhileTemplateUnavailable() throws Exception {
        String id = running("Resume intake", 1);
        templateStatus("PAUSED");
        tickNow();
        ResponseEntity<JsonNode> refused = resume(id);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refused.getBody().get("message").asString()).contains("not approved");
        assertThat(campaign(id).getStatus()).isEqualTo(CampaignStatus.PAUSED);
        templateStatus("APPROVED");
        assertThat(resume(id).getStatusCode()).isEqualTo(HttpStatus.OK);
        GRAPH.enqueue("POST /333/messages", 200, sent("wamid.R1"));
        tickNow();
        assertThat(recipients(id).get(0).getStatus()).isEqualTo(RecipientStatus.SENT);
    }

    @Test
    void webResumeUnchanged() {
        contact("Asha", "919811177701");
        String t = template("Hi", "Hi {{contact.name}}");
        ResponseEntity<JsonNode> created = ws(HttpMethod.POST, "/campaigns", manager, Map.of("name", "Web intake",
                "audience", Map.of("kind", "FILTER", "filterId", allContactsFilter("All web")), "templateIds",
                List.of(t), "placeholders", List.of(), "senderIds", List.of(n1.getId())));
        assertThat(created.getStatusCode()).as(String.valueOf(created.getBody())).isEqualTo(HttpStatus.CREATED);
        String id = created.getBody().get("id").asString();
        start(id);
        assertThat(ws(HttpMethod.POST, "/campaigns/" + id + "/pause", manager, null).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(resume(id).getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
