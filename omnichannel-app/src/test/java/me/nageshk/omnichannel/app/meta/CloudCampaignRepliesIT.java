package me.nageshk.omnichannel.app.meta;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import me.nageshk.omnichannel.db.campaign.Campaign;
import me.nageshk.omnichannel.db.campaign.CampaignRecipient;
import me.nageshk.omnichannel.db.campaign.RecipientStatus;
import me.nageshk.omnichannel.db.contact.Contact;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Meta's statuses and the customer's replies reach a Cloud campaign's recipients (spec 2026-11-21 §5.4, §5.6). */
class CloudCampaignRepliesIT extends CloudCampaignTestBase {

    private static final String PHONE = "919811155501";
    private String id;

    @BeforeEach
    void sentOne() throws Exception {
        String t = syncTemplate(FOLLOW_UP);
        id = cloudCampaign("Replies intake", t, List.of(PHONE), nameIntoBody1(), numberId);
        start(id);
        GRAPH.enqueue("POST /333/messages", 200, sent("wamid.C1"));
        tickNow();
        assertThat(recipient().getStatus()).isEqualTo(RecipientStatus.SENT);
    }

    private CampaignRecipient recipient() {
        return recipients(id).get(0);
    }

    private Campaign campaign() {
        return mongo.findById(id, Campaign.class);
    }

    private void status(String status, String errors) {
        webhook(webhookKey, envelope("{\"field\":\"messages\",\"value\":{\"messaging_product\":\"whatsapp\","
                + "\"metadata\":{\"display_phone_number\":\"919876543210\",\"phone_number_id\":\"333\"},\"statuses\":"
                + "[{\"id\":\"wamid.C1\",\"status\":\"" + status + "\",\"timestamp\":\"" + Instant.now().getEpochSecond()
                + "\",\"recipient_id\":\"" + PHONE + "\"" + (errors == null ? "" : ",\"errors\":[" + errors + "]")
                + "}]}}"));
    }

    private void inbound(String wamid, String messageJson) {
        webhook(webhookKey, envelope("{\"field\":\"messages\",\"value\":{\"messaging_product\":\"whatsapp\","
                + "\"metadata\":{\"display_phone_number\":\"919876543210\",\"phone_number_id\":\"333\"},"
                + "\"contacts\":[{\"profile\":{\"name\":\"Lead\"},\"wa_id\":\"" + PHONE + "\"}],\"messages\":[{\"from\":\""
                + PHONE + "\",\"id\":\"" + wamid + "\",\"timestamp\":\"" + Instant.now().getEpochSecond() + "\","
                + messageJson + "}]}}"));
    }

    @Test
    void deliveredAndReadCount() throws Exception {
        status("delivered", null);
        await(() -> recipient().getStatus() == RecipientStatus.DELIVERED);
        status("read", null);
        await(() -> recipient().getStatus() == RecipientStatus.READ);
        await(() -> campaign().getCounts().read() == 1);
    }

    @Test
    void failedStatusFailsTheRecipient() throws Exception {
        status("failed", "{\"code\":131026,\"title\":\"Message undeliverable\"}");
        await(() -> recipient().getStatus() == RecipientStatus.FAILED);
        assertThat(recipient().getError()).isEqualTo("131026 Message undeliverable");
        await(() -> campaign().getCounts().failed() == 1 && campaign().getCounts().sent() == 0);
    }

    @Test
    void heldBackMarketingFails() throws Exception {
        status("failed", "{\"code\":131049,\"title\":\"This message was not delivered to maintain healthy ecosystem engagement.\"}");
        await(() -> recipient().getStatus() == RecipientStatus.FAILED);
        assertThat(recipient().getError()).startsWith("131049 ");
        assertThat(cloudSenders.heldUntil(numberId, clock.instant())).isNull();
    }

    @Test
    void replyCountsAndStopWordStops() throws Exception {
        inbound("wamid.IN1", "\"type\":\"text\",\"text\":{\"body\":\"Interested\"}");
        await(() -> recipient().getRepliedAt() != null);
        await(() -> campaign().getCounts().replied() == 1);
        inbound("wamid.IN2", "\"type\":\"text\",\"text\":{\"body\":\"STOP\"}");
        await(() -> {
            Contact c = mongo.findOne(new org.springframework.data.mongodb.core.query.Query(
                    org.springframework.data.mongodb.core.query.Criteria.where("phone").is(PHONE)), Contact.class);
            return c != null && c.getDoNotContact() != null;
        });
    }

    @Test
    void quickReplyButtonCountsAsReply() throws Exception {
        inbound("wamid.IN3", "\"type\":\"button\",\"button\":{\"text\":\"Call me\",\"payload\":\"Call me\"}");
        await(() -> recipient().getRepliedAt() != null);
    }
}
