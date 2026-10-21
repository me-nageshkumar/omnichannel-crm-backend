package me.nageshk.omnichannel.campaign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.PUT;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import me.nageshk.omnichannel.enablement.Disabled;
import me.nageshk.omnichannel.enablement.EnableLevel;
import me.nageshk.omnichannel.phone.ConnectionType;
import me.nageshk.omnichannel.phone.PhoneNumber;
import me.nageshk.omnichannel.phone.PhoneNumberRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

class CampaignSenderIT extends CampaignTestBase {

    @Autowired PhoneNumberRepository numbers;

    private JsonNode list() {
        return api(GET, "/campaigns/senders", manager, null).getBody();
    }

    private JsonNode view(String phoneNumberId) {
        for (JsonNode v : list()) if (v.get("phoneNumberId").asString().equals(phoneNumberId)) return v;
        throw new AssertionError("no sender view for " + phoneNumberId);
    }

    private ResponseEntity<JsonNode> put(PhoneNumber n, Map<String, Object> body) {
        return api(PUT, "/campaigns/senders/" + n.getId(), manager, body);
    }

    private void attempt(PhoneNumber n, RecipientStatus status, Instant attemptedAt, int i) {
        CampaignRecipient r = new CampaignRecipient();
        r.setCampaignId("c1");
        r.setWorkspaceId(brand.getId());
        r.setPhone("91981100000" + i);
        r.setSenderId(n.getId());
        r.setStatus(status);
        r.setAttemptedAt(attemptedAt);
        mongo.save(r);
    }

    @Test
    void listShowsEveryEnabledWebNumberAsWarming() {
        PhoneNumber disabled = testDevices.number(brand, "9195" + (System.nanoTime() % 100000000));
        disabled.setDisabled(new Disabled("x", EnableLevel.BRAND, WED_11_IST));
        numbers.save(disabled);
        PhoneNumber cloud = testDevices.number(brand, "9194" + (System.nanoTime() % 100000000));
        cloud.setConnectionType(ConnectionType.WHATSAPP_CLOUD_API);
        numbers.save(cloud);

        JsonNode body = list();

        assertThat(body).hasSize(2);
        for (JsonNode v : body) {
            assertThat(v.get("state").asString()).isEqualTo("WARMING");
            assertThat(v.get("ageDay").asInt()).isEqualTo(1);
            assertThat(v.get("capToday").asInt()).isEqualTo(5);
            assertThat(v.get("usedToday").asInt()).isZero();
            assertThat(v.get("phoneStatus").asString()).isEqualTo("NOT_CONNECTED_YET");
            assertThat(v.get("ready").asBoolean()).isFalse();
        }
        List<String> ids = body.valueStream().map(v -> v.get("phoneNumberId").asString()).toList();
        assertThat(ids).containsExactlyInAnyOrder(n1.getId(), n2.getId());
        assertThat(mongo.findAll(CampaignSender.class)).hasSize(2);
    }

    @Test
    void usageCountsAttemptsOfTodayOnly() {
        attempt(n1, RecipientStatus.SENT, Instant.parse("2026-10-21T05:00:00Z"), 1);
        attempt(n1, RecipientStatus.FAILED, Instant.parse("2026-10-21T05:10:00Z"), 2);
        attempt(n1, RecipientStatus.SKIPPED, Instant.parse("2026-10-21T05:20:00Z"), 3);
        attempt(n1, RecipientStatus.SENT, Instant.parse("2026-10-20T11:30:00Z"), 4);

        JsonNode v = view(n1.getId());

        assertThat(v.get("usedToday").asInt()).isEqualTo(2);
        assertThat(v.get("hourlyUsed").asInt()).isEqualTo(3);
    }

    @Test
    void restRetireReactivateResume() {
        ResponseEntity<JsonNode> rested = put(n1, Map.of("action", "REST"));
        assertThat(rested.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(rested.getBody().get("state").asString()).isEqualTo("RESTING");
        assertThat(Instant.parse(rested.getBody().get("restUntil").asString()))
                .isEqualTo(WED_11_IST.plus(Duration.ofDays(7)));

        ResponseEntity<JsonNode> again = put(n1, Map.of("action", "REST"));
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(again.getBody().get("code").asString()).isEqualTo("SENDER_STATE");
        assertThat(again.getBody().get("message").asString()).isEqualTo("This number cannot be rested now.");

        JsonNode reactivated = put(n1, Map.of("action", "REACTIVATE")).getBody();
        assertThat(reactivated.get("state").asString()).isEqualTo("WARMING");
        assertThat(reactivated.get("warmupStartedAt").isNull()).isTrue();
        assertThat(reactivated.get("ageDay").asInt()).isEqualTo(1);

        assertThat(put(n1, Map.of("action", "RETIRE")).getBody().get("state").asString()).isEqualTo("RETIRED");

        ResponseEntity<JsonNode> resume = put(n1, Map.of("action", "RESUME"));
        assertThat(resume.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(resume.getBody().get("message").asString()).isEqualTo("This number cannot be resumed now.");

        sender(n2, s -> {
            s.setPausedUntil(WED_11_IST.plus(Duration.ofHours(1)));
            s.setPauseReason("two logouts");
        });
        assertThat(view(n2.getId()).get("pausedUntil").asString()).isNotBlank();
        ResponseEntity<JsonNode> resumed = put(n2, Map.of("action", "RESUME"));
        assertThat(resumed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resumed.getBody().get("pausedUntil").isNull()).isTrue();
        assertThat(resumed.getBody().get("pauseReason").isNull()).isTrue();
    }

    @Test
    void restingReturnsToWarmingAtRestUntil() {
        put(n1, Map.of("action", "REST"));
        clock.advance(Duration.ofDays(7));

        JsonNode v = view(n1.getId());

        assertThat(v.get("state").asString()).isEqualTo("WARMING");
        assertThat(sender(n1).getSendingDays()).isZero();
        assertThat(sender(n1).getRestUntil()).isNull();
    }

    @Test
    void warmingBecomesActiveOnDay31() {
        sender(n1, s -> {
            s.setSendingDays(30);
            s.setLastSendingDay(LocalDate.of(2026, 10, 20));
        });

        JsonNode v = view(n1.getId());

        assertThat(v.get("state").asString()).isEqualTo("ACTIVE");
        assertThat(v.get("capToday").asInt()).isEqualTo(100);
    }

    @Test
    void runsOnIsSetWithoutAnAction() {
        assertThat(put(n1, Map.of("runsOn", " Office PC, profile 3 ")).getBody().get("runsOn").asString())
                .isEqualTo("Office PC, profile 3");
        assertThat(put(n1, Map.of("runsOn", "")).getBody().get("runsOn").isNull()).isTrue();
        put(n1, Map.of("runsOn", "Laptop"));
        JsonNode retired = put(n1, Map.of("runsOn", "x", "action", "RETIRE")).getBody();
        assertThat(retired.get("state").asString()).isEqualTo("RETIRED");
        assertThat(retired.get("runsOn").asString()).isEqualTo("Laptop");

        ResponseEntity<JsonNode> tooLong = put(n2, Map.of("runsOn", "x".repeat(101)));
        assertThat(tooLong.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(tooLong.getBody().get("code").asString()).isEqualTo("VALIDATION");
    }

    @Test
    void sendersNeedTheirAuthorities() {
        assertThat(api(GET, "/campaigns/senders", agent, null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(api(PUT, "/campaigns/senders/" + n1.getId(), agent, Map.of("action", "REST")).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(api(PUT, "/campaigns/senders/" + n1.getId(), lead, Map.of("action", "REST")).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(put(otherNumber, Map.of("action", "REST")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void dayStartOffsetIsDrawnOncePerDay() {
        random.offset = 37;
        list();
        list();
        assertThat(sender(n1).getDayStartOffsetMinutes()).isEqualTo(37);
        assertThat(sender(n1).getDayStartFor()).isEqualTo(LocalDate.of(2026, 10, 21));

        random.offset = 5;
        list();
        assertThat(sender(n1).getDayStartOffsetMinutes()).isEqualTo(37);

        clock.advance(Duration.ofDays(1));
        list();
        assertThat(sender(n1).getDayStartOffsetMinutes()).isEqualTo(5);
    }
}
