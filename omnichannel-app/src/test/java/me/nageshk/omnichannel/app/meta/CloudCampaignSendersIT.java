package me.nageshk.omnichannel.app.meta;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import me.nageshk.omnichannel.db.inbox.MessageDirection;
import me.nageshk.omnichannel.db.inbox.WaWebMessage;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import tools.jackson.databind.JsonNode;

/** The Cloud numbers a campaign may send from, with Meta's daily limit (spec 2026-11-21 §5.3, §5.8). */
class CloudCampaignSendersIT extends CloudCampaignTestBase {

    private JsonNode cloudList() {
        return ws(HttpMethod.GET, "/campaigns/senders?channel=WHATSAPP_CLOUD_API", manager, null).getBody();
    }

    private void templateSent(String chatId, Instant at, boolean failed) {
        WaWebMessage m = new WaWebMessage();
        m.setWorkspaceId(brand.getId());
        m.setPhoneNumberId(numberId);
        m.setChatId(chatId);
        m.setWaMessageId("wamid." + COUNTER.incrementAndGet());
        m.setDirection(MessageDirection.OUT);
        m.setCloudTemplateId("t1");
        m.setTimestamp(at);
        if (failed) m.setFailure(new WaWebMessage.Failure(131026, "Message undeliverable", null));
        mongo.save(m);
    }

    @Test
    void listsCloudNumbersWithLimit() {
        JsonNode list = cloudList();
        assertThat(list).hasSize(1);
        JsonNode row = list.get(0);
        assertThat(row.get("phoneNumberId").asString()).isEqualTo(numberId);
        assertThat(row.get("wabaId").asString()).isEqualTo("222");
        assertThat(row.get("label").asString()).isEqualTo("Admissions");
        assertThat(row.get("canSend").asBoolean()).isTrue();
        assertThat(row.get("qualityRating").asString()).isEqualTo("GREEN");
        assertThat(row.get("messagingLimitTier").asString()).isEqualTo("TIER_250");
        assertThat(row.get("dailyLimit").asInt()).isEqualTo(250);
        assertThat(row.get("usedLast24h").asInt()).isZero();
        assertThat(row.get("heldUntil").isNull()).isTrue();
    }

    @Test
    void webListUnchanged() {
        JsonNode list = ws(HttpMethod.GET, "/campaigns/senders", manager, null).getBody();
        assertThat(list.findValuesAsString("phoneNumberId")).doesNotContain(numberId).contains(n1.getId());
    }

    @Test
    void usedCountsInboxTemplatesOfTheWaba() {
        Instant now = clock.instant();
        templateSent("chatA", now.minus(Duration.ofHours(1)), false);
        templateSent("chatA", now.minus(Duration.ofHours(2)), false);
        templateSent("chatB", now.minus(Duration.ofHours(23)), false);
        templateSent("chatC", now.minus(Duration.ofHours(25)), false);
        templateSent("chatD", now.minus(Duration.ofMinutes(5)), true);
        assertThat(cloudList().get(0).get("usedLast24h").asInt()).isEqualTo(2);
    }

    @Test
    void heldNumberShowsHeldUntil() {
        Instant until = clock.instant().plus(Duration.ofHours(1));
        cloudSenders.holdWaba("222", until);
        assertThat(Instant.parse(cloudList().get(0).get("heldUntil").asString())).isEqualTo(until);
    }
}
