package me.nageshk.omnichannel.app.meta;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import me.nageshk.omnichannel.db.campaign.Campaign;
import me.nageshk.omnichannel.db.campaign.CampaignRecipient;
import me.nageshk.omnichannel.db.campaign.CampaignStatus;
import me.nageshk.omnichannel.db.campaign.RecipientStatus;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/** The final review's findings on Cloud campaigns, each pinned (spec 2026-11-21 §5.3, §5.4). */
class CloudCampaignFixesIT extends CloudCampaignTestBase {

    private String started(String name, int n, String... senders) {
        String t = syncTemplate(FOLLOW_UP);
        List<String> phones = new ArrayList<>();
        for (int i = 0; i < n; i++) phones.add("9196" + String.format("%08d", 500 + i));
        String id = cloudCampaign(name, t, phones, nameIntoBody1(), senders.length == 0 ? new String[] {numberId}
                : senders);
        start(id);
        return id;
    }

    private static String metaError(int code, String message) {
        return "{\"error\":{\"message\":\"" + message + "\",\"type\":\"OAuthException\",\"code\":" + code + "}}";
    }

    private long count(String id, RecipientStatus s) {
        return recipients(id).stream().filter(r -> r.getStatus() == s).count();
    }

    private Campaign campaign(String id) {
        return mongo.findById(id, Campaign.class);
    }

    @Test
    void templatePausedRefusalPausesNotFails() throws Exception {
        String id = started("Paused template", 4);
        GRAPH.enqueue("POST /333/messages", 400, metaError(132015, "Template is paused"));
        tickNow();
        assertThat(count(id, RecipientStatus.PENDING)).isEqualTo(4);
        assertThat(campaign(id).getStatus()).isEqualTo(CampaignStatus.PAUSED);
        assertThat(campaign(id).getPauseReason()).isEqualTo("TEMPLATE_UNAVAILABLE");
        assertThat(GRAPH.recorded("POST /333/messages")).hasSize(1);
    }

    @Test
    void accountRefusalPausesNotFails() throws Exception {
        String id = started("Unpaid account", 4);
        GRAPH.enqueue("POST /333/messages", 400, metaError(131042, "Business eligibility payment issue"));
        tickNow();
        assertThat(count(id, RecipientStatus.PENDING)).isEqualTo(4);
        assertThat(campaign(id).getPauseReason()).isEqualTo("SEND_REFUSED");
    }

    @Test
    void repeatedRefusalTripsTheBreaker() throws Exception {
        String id = started("Same refusal", 5);
        for (int i = 0; i < 5; i++) {
            GRAPH.enqueue("POST /333/messages", 400, metaError(131008, "Required parameter is missing"));
        }
        tickNow();
        assertThat(GRAPH.recorded("POST /333/messages")).hasSize(3);
        assertThat(count(id, RecipientStatus.FAILED)).isEqualTo(2);
        assertThat(count(id, RecipientStatus.PENDING)).isEqualTo(3);
        assertThat(campaign(id).getPauseReason()).isEqualTo("SEND_REFUSED");
    }

    @Test
    void refusedRecipientIsNotRecentlyMessaged() throws Exception {
        String id = started("Refused one", 2);
        GRAPH.enqueue("POST /333/messages", 400, metaError(131008, "Required parameter is missing"));
        GRAPH.enqueue("POST /333/messages", 200, sent("wamid.X2"));
        tickNow();
        CampaignRecipient refused = recipients(id).get(0);
        assertThat(refused.getStatus()).isEqualTo(RecipientStatus.FAILED);
        assertThat(refused.getDispatchedAt()).isNull();
        assertThat(recipients(id).get(1).getDispatchedAt()).isNotNull();
    }

    @Test
    void numbersOfOneWabaShareTheDailyLimit() throws Exception {
        String second = addCloudNumber(connectionId, "Second", "334", "+91 98765 43211").get("id").asString();
        GRAPH.reset();
        mongo.updateMulti(new Query(), new Update().set("messagingLimitTier", "TIER_50"), "cloudAccounts");
        String id = started("Shared limit", 60, numberId, second);
        for (int i = 0; i < 60; i++) {
            GRAPH.enqueue("POST /333/messages", 200, sent("wamid.S" + i));
            GRAPH.enqueue("POST /334/messages", 200, sent("wamid.T" + i));
        }
        tickNow();
        tickNow();
        assertThat(count(id, RecipientStatus.SENT)).isEqualTo(50);
    }

    @Test
    void the24HourCountHasAnIndex() {
        List<Document> keys = new ArrayList<>();
        mongo.getCollection("waWebMessages").listIndexes().forEach(d -> keys.add((Document) d.get("key")));
        assertThat(keys).anySatisfy(k -> {
            assertThat(k.keySet()).containsExactly("phoneNumberId", "timestamp");
            assertThat(k.get("timestamp")).isEqualTo(-1);
        });
    }
}
