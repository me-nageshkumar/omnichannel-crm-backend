package me.nageshk.omnichannel.campaign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.PUT;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import me.nageshk.omnichannel.phone.PhoneNumber;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** The automatic pauses: a run of undelivered sends, and a low reply rate on the day roll. */
class SenderHealthIT extends CampaignTestBase {

    static final Instant END_OF_WED = Instant.parse("2026-10-21T18:30:00Z");

    private int seeded;

    private void seed(PhoneNumber n, RecipientStatus status, Instant sentAt, boolean replied) {
        CampaignRecipient r = new CampaignRecipient();
        r.setCampaignId("seed");
        r.setWorkspaceId(brand.getId());
        r.setPhone("9199000" + String.format("%05d", ++seeded));
        r.setStatus(status);
        r.setSenderId(n.getId());
        r.setSentAt(sentAt);
        r.setRepliedAt(replied ? sentAt.plusSeconds(60) : null);
        mongo.save(r);
    }

    /** Five SENT sends 11 to 15 minutes old. */
    private void fiveUndelivered(PhoneNumber n) {
        for (int i = 11; i <= 15; i++) seed(n, RecipientStatus.SENT, WED_11_IST.minus(Duration.ofMinutes(i)), false);
    }

    private JsonNode view(PhoneNumber n) {
        JsonNode all = api(GET, "/campaigns/senders", manager, null).getBody();
        for (JsonNode v : all) if (v.get("phoneNumberId").asString().equals(n.getId())) return v;
        throw new AssertionError("no sender view");
    }

    private long sent(String campaignId) {
        return campaign(campaignId).getCounts().sent();
    }

    @Test
    void fiveUndeliveredSendsPauseForTheDay() throws Exception {
        try (AutoDevice d = device(n1)) {
            fiveUndelivered(n1);
            String id = startedFilterCampaign("Run", List.of("919811100001", "919811100002", "919811100003"), n1);
            tickNow();
            assertThat(sender(n1).getPausedUntil()).isEqualTo(END_OF_WED);
            assertThat(sender(n1).getPauseReason()).isEqualTo("UNDELIVERED_RUN");
            assertThat(view(n1).get("pauseReason").asString()).isEqualTo("UNDELIVERED_RUN");
            clock.advance(Duration.ofMinutes(5));
            tickNow();
            assertThat(sent(id)).isEqualTo(1);
            clock.advance(Duration.ofHours(23));          // Thursday 10:00 IST
            tickNow();
            assertThat(sent(id)).isEqualTo(2);
        }
    }

    @Test
    void oneDeliveredBreaksTheRun() throws Exception {
        try (AutoDevice d = device(n1)) {
            for (int i = 11; i <= 14; i++) seed(n1, RecipientStatus.SENT, WED_11_IST.minus(Duration.ofMinutes(i)), false);
            seed(n1, RecipientStatus.DELIVERED, WED_11_IST.minus(Duration.ofMinutes(15)), false);
            startedFilterCampaign("Run", List.of("919811100001"), n1);
            tickNow();
            assertThat(sender(n1).getPausedUntil()).isNull();
        }
    }

    @Test
    void youngSendsDoNotCount() throws Exception {
        try (AutoDevice d = device(n1)) {
            fiveUndelivered(n1);
            seed(n1, RecipientStatus.SENT, WED_11_IST.minus(Duration.ofMinutes(1)), false);
            startedFilterCampaign("Run", List.of("919811100001"), n1);
            tickNow();
            assertThat(sender(n1).getPausedUntil()).isEqualTo(END_OF_WED);
        }
    }

    private void dayRollsOnThursday() {
        sender(n1, s -> s.setDayStartFor(LocalDate.of(2026, 10, 20)));
    }

    @Test
    void lowReplyRateHalvesFromTheNextDay() {
        for (int i = 0; i < 30; i++)
            seed(n1, RecipientStatus.SENT, WED_11_IST.minus(Duration.ofHours(25 + i)), i < 2);
        dayRollsOnThursday();
        JsonNode v = view(n1);
        assertThat(v.get("lowReplyRate").asBoolean()).isTrue();
        assertThat(v.get("capToday").asInt()).isEqualTo(2);
    }

    @Test
    void enoughRepliesClearIt() {
        sender(n1, s -> s.setLowReplyRate(true));
        for (int i = 0; i < 30; i++)
            seed(n1, RecipientStatus.SENT, WED_11_IST.minus(Duration.ofHours(25 + i)), i < 3);
        dayRollsOnThursday();
        assertThat(view(n1).get("lowReplyRate").asBoolean()).isFalse();
    }

    @Test
    void fewerThanThirtyKeepsTheFlag() {
        sender(n1, s -> s.setLowReplyRate(true));
        for (int i = 0; i < 29; i++)
            seed(n1, RecipientStatus.SENT, WED_11_IST.minus(Duration.ofHours(25 + i)), false);
        dayRollsOnThursday();
        assertThat(view(n1).get("lowReplyRate").asBoolean()).isTrue();
    }

    @Test
    void resumeClearsAnActivePause() throws Exception {
        try (AutoDevice d = device(n1)) {
            fiveUndelivered(n1);
            String id = startedFilterCampaign("Run", List.of("919811100001", "919811100002"), n1);
            tickNow();
            assertThat(sender(n1).getPausedUntil()).isNotNull();
            api(PUT, "/campaigns/senders/" + n1.getId(), manager, Map.of("action", "RESUME"));
            assertThat(sender(n1).getPausedUntil()).isNull();
            clock.advance(Duration.ofMinutes(5));
            tickNow();
            assertThat(sent(id)).isEqualTo(2);
        }
    }
}
