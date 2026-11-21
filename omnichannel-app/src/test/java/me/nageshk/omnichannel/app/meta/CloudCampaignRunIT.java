package me.nageshk.omnichannel.app.meta;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import me.nageshk.omnichannel.db.campaign.Campaign;
import me.nageshk.omnichannel.db.campaign.CampaignRecipient;
import me.nageshk.omnichannel.db.campaign.CampaignSender;
import me.nageshk.omnichannel.db.campaign.CampaignStatus;
import me.nageshk.omnichannel.db.campaign.RecipientStatus;
import me.nageshk.omnichannel.db.contact.Contact;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/** A Cloud campaign sends as fast as Meta allows, inside the window and Meta's daily limit (spec 2026-11-21 §5.3). */
class CloudCampaignRunIT extends CloudCampaignTestBase {

    private static List<String> phones(int n, int from) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add("9198" + String.format("%08d", from + i));
        return out;
    }

    private String startedCampaign(String name, int n) {
        String t = syncTemplate(FOLLOW_UP);
        String id = cloudCampaign(name, t, phones(n, 100), nameIntoBody1(), numberId);
        start(id);
        return id;
    }

    private void acceptAll(int n) {
        for (int i = 0; i < n; i++) GRAPH.enqueue("POST /333/messages", 200, sent("wamid." + COUNTER.incrementAndGet()));
    }

    private long count(String id, RecipientStatus s) {
        return recipients(id).stream().filter(r -> r.getStatus() == s).count();
    }

    private Campaign campaign(String id) {
        return mongo.findById(id, Campaign.class);
    }

    private void tier(String tier) {
        mongo.updateFirst(new Query(Criteria.where("phoneNumberId").is(numberId)),
                new Update().set("messagingLimitTier", tier), "cloudAccounts");
    }

    private static String metaError(int code, String message) {
        return "{\"error\":{\"message\":\"" + message + "\",\"type\":\"OAuthException\",\"code\":" + code
                + ",\"error_data\":{\"details\":\"" + message + "\"}}}";
    }

    @Test
    void sendsEveryRecipientInOneTick() throws Exception {
        String id = startedCampaign("Spring intake", 5);
        acceptAll(5);
        tickNow();
        assertThat(count(id, RecipientStatus.SENT)).isEqualTo(5);
        assertThat(GRAPH.recorded("POST /333/messages")).hasSize(5);
        assertThat(campaign(id).getCounts().sent()).isEqualTo(5);
        assertThat(mongo.findById(numberId, CampaignSender.class)).isNull();
        tickNow();
        assertThat(campaign(id).getStatus()).isEqualTo(CampaignStatus.COMPLETED);
    }

    @Test
    void stopsAtTheDailyLimitAndGoesOnWhenItRolls() throws Exception {
        tier("TIER_50");
        String id = startedCampaign("Big intake", 60);
        acceptAll(60);
        tickNow();
        tickNow();
        assertThat(count(id, RecipientStatus.SENT)).isEqualTo(50);
        assertThat(count(id, RecipientStatus.PENDING)).isEqualTo(10);
        clock.set(clock.instant().plus(Duration.ofHours(24)).plus(Duration.ofMinutes(1)));
        tickNow();
        assertThat(count(id, RecipientStatus.SENT)).isEqualTo(60);
    }

    @Test
    void limitRefusalHoldsTheWaba() throws Exception {
        String id = startedCampaign("Held intake", 3);
        GRAPH.enqueue("POST /333/messages", 400, metaError(131048, "Spam rate limit hit"));
        tickNow();
        assertThat(count(id, RecipientStatus.PENDING)).isEqualTo(3);
        assertThat(GRAPH.recorded("POST /333/messages")).hasSize(1);
        assertThat(cloudSenders.heldUntil(numberId, clock.instant())).isEqualTo(clock.instant().plus(Duration.ofHours(1)));
        tickNow();
        assertThat(GRAPH.recorded("POST /333/messages")).hasSize(1);
    }

    @Test
    void rateLimitHoldsOnlyRetryAfter() throws Exception {
        String id = startedCampaign("Slow intake", 1);
        GRAPH.enqueue("POST /333/messages", 400, metaError(130429, "Rate limit hit"));
        tickNow();
        assertThat(count(id, RecipientStatus.PENDING)).isEqualTo(1);
        clock.set(clock.instant().plus(Duration.ofSeconds(61)));
        acceptAll(1);
        tickNow();
        assertThat(count(id, RecipientStatus.SENT)).isEqualTo(1);
    }

    @Test
    void refusalFailsOnlyThatRecipient() throws Exception {
        String id = startedCampaign("Refused intake", 3);
        GRAPH.enqueue("POST /333/messages", 400, metaError(131008, "Required parameter is missing"));
        acceptAll(2);
        tickNow();
        List<CampaignRecipient> rs = recipients(id);
        assertThat(rs.get(0).getStatus()).isEqualTo(RecipientStatus.FAILED);
        assertThat(rs.get(0).getError()).startsWith("131008 ");
        assertThat(count(id, RecipientStatus.SENT)).isEqualTo(2);
        assertThat(campaign(id).getStatus()).isEqualTo(CampaignStatus.RUNNING);
    }

    @Test
    void blankValueFailsRecipientNotCampaign() throws Exception {
        String t = syncTemplate(FOLLOW_UP);
        Contact nameless = contact("", "919800000001");
        contact("Asha", "919800000002");
        List<Map<String, Object>> noFallback = List.of(Map.of("name", "body:1", "source",
                Map.of("kind", "CONTACT_FIELD", "field", "name"), "fallback", ""), nameIntoBody1().get(1));
        String id = cloudCampaign("Nameless intake", t, List.of(), noFallback, numberId);
        start(id);
        acceptAll(1);
        tickNow();
        CampaignRecipient blank = recipients(id).stream().filter(r -> r.getPhone().equals(nameless.getPhone()))
                .findFirst().orElseThrow();
        assertThat(blank.getStatus()).isEqualTo(RecipientStatus.FAILED);
        assertThat(blank.getError()).isEqualTo("MISSING_VALUE");
        assertThat(count(id, RecipientStatus.SENT)).isEqualTo(1);
        assertThat(campaign(id).getStatus()).isEqualTo(CampaignStatus.RUNNING);
    }

    @Test
    void unknownOutcomeIsNeverRetried() throws Exception {
        String id = startedCampaign("Unknown intake", 1);
        GRAPH.enqueue("POST /333/messages", 503, "{}");
        tickNow();
        CampaignRecipient r = recipients(id).get(0);
        assertThat(r.getStatus()).isEqualTo(RecipientStatus.FAILED);
        assertThat(r.getError()).isEqualTo("OUTCOME_UNKNOWN");
        tickNow();
        assertThat(GRAPH.recorded("POST /333/messages")).hasSize(1);
    }

    @Test
    void pausedMidLoopSendsNoMore() throws Exception {
        String id = startedCampaign("Paused intake", 5);
        acceptAll(5);
        GRAPH.before("POST /333/messages", () -> GRAPH.before("POST /333/messages", () -> mongo.updateFirst(
                new Query(Criteria.where("_id").is(id)), new Update().set("status", CampaignStatus.PAUSED),
                Campaign.class)));
        tickNow();
        assertThat(GRAPH.recorded("POST /333/messages")).hasSize(2);
        assertThat(count(id, RecipientStatus.PENDING)).isEqualTo(3);
    }

    @Test
    void windowClosingStopsTheLoop() throws Exception {
        String id = startedCampaign("Late intake", 3);
        clock.set(Instant.parse("2026-11-03T12:29:59Z")); // 17:59:59 IST
        acceptAll(3);
        GRAPH.before("POST /333/messages", () -> clock.set(clock.instant().plusSeconds(2)));
        tickNow();
        assertThat(count(id, RecipientStatus.SENT)).isEqualTo(1);
        assertThat(count(id, RecipientStatus.PENDING)).isEqualTo(2);
    }

    @Test
    void outsideWindowNothingSends() throws Exception {
        startedCampaign("Sunday intake", 2);
        clock.set(Instant.parse("2026-11-08T05:30:00Z")); // Sunday 11:00 IST
        tickNow();
        assertThat(GRAPH.recorded("POST /333/messages")).isEmpty();
    }

    @Test
    void disabledPreferredNumberSkipsItsRecipients() throws Exception {
        String t = syncTemplate(FOLLOW_UP);
        String id = cloudCampaign("Preferred intake", t, phones(1, 900), nameIntoBody1(), numberId);
        start(id);
        mongo.updateMulti(new Query(Criteria.where("campaignId").is(id)), new Update().set("preferredSenderId", "gone"),
                CampaignRecipient.class);
        tickNow();
        assertThat(recipients(id).get(0).getStatus()).isEqualTo(RecipientStatus.SKIPPED);
    }
}
