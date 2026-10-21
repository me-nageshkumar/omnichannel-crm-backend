package me.nageshk.omnichannel.campaign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpMethod.GET;

import java.time.Duration;
import java.time.Instant;
import me.nageshk.omnichannel.events.WhatsAppEvent;
import me.nageshk.omnichannel.phone.PhoneNumber;
import me.nageshk.omnichannel.tenant.Workspace;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class SenderEventsIT extends CampaignTestBase {

    @Autowired ApplicationEventPublisher publisher;
    @Autowired JsonMapper mapper;

    private void publish(PhoneNumber n, String name, JsonNode payload) {
        publisher.publishEvent(new WhatsAppEvent(this, n.getId(), n.getWorkspaceId(), acme.getId(), name, payload,
                null, null));
    }

    private void state(PhoneNumber n, String state) {
        publish(n, "change_state", mapper.createObjectNode().put("state", state));
    }

    private void logout(PhoneNumber n) {
        state(n, "UNPAIRED");
    }

    private Campaign campaign(Workspace w, String name, CampaignStatus status, PausedBy by) {
        Campaign c = new Campaign();
        c.setWorkspaceId(w.getId());
        c.setCustomerId(acme.getId());
        c.setName(name);
        c.setNameKey(name.toLowerCase());
        c.setStatus(status);
        c.setPausedBy(by);
        return mongo.save(c);
    }

    @Test
    void logoutSuspendsAndHalvesTheOthersForTheDay() {
        Campaign running = campaign(brand, "run", CampaignStatus.RUNNING, null);
        sender(n2);
        logout(n1);
        CampaignSender s1 = sender(n1), s2 = sender(n2);
        assertThat(s1.getState()).isEqualTo(SenderState.SUSPENDED);
        assertThat(s1.getLogoutAt()).isEqualTo(WED_11_IST);
        assertThat(s2.getState()).isEqualTo(SenderState.WARMING);
        assertThat(s2.getHalfPaceUntil()).isEqualTo(Instant.parse("2026-10-21T18:30:00Z"));
        assertThat(s1.getHalfPaceUntil()).isNull();
        assertThat(campaign(running.getId()).getStatus()).isEqualTo(CampaignStatus.RUNNING);
    }

    @Test
    void retiredSendersAreNotHalved() {
        sender(n2, s -> s.setState(SenderState.RETIRED));
        logout(n1);
        assertThat(sender(n2).getHalfPaceUntil()).isNull();
    }

    @Test
    void reconnectionRestsThenWarmingAfterSevenDays() {
        logout(n1);
        publish(n1, "ready", mapper.createObjectNode());
        CampaignSender rested = sender(n1);
        assertThat(rested.getState()).isEqualTo(SenderState.RESTING);
        assertThat(rested.getRestUntil()).isEqualTo(WED_11_IST.plus(Duration.ofDays(7)));

        clock.set(WED_11_IST.plus(Duration.ofDays(7)));
        JsonNode list = api(GET, "/campaigns/senders", manager, null).getBody();
        JsonNode v = null;
        for (JsonNode x : list) if (x.get("phoneNumberId").asString().equals(n1.getId())) v = x;
        assertThat(v).isNotNull();
        assertThat(v.get("state").asString()).isEqualTo("WARMING");
        assertThat(v.get("ageDay").asInt()).isEqualTo(1);
        CampaignSender s = sender(n1);
        assertThat(s.getState()).isEqualTo(SenderState.WARMING);
        assertThat(s.getWarmupStartedAt()).isNull();
    }

    @Test
    void twoLogoutsWithinAWeekPauseRunningCampaigns() {
        Campaign running = campaign(brand, "run", CampaignStatus.RUNNING, null);
        Campaign draft = campaign(brand, "draft", CampaignStatus.DRAFT, null);
        Campaign byUser = campaign(brand, "user", CampaignStatus.PAUSED, PausedBy.USER);
        Campaign foreign = campaign(other, "foreign", CampaignStatus.RUNNING, null);
        logout(n1);
        assertThat(campaign(running.getId()).getStatus()).isEqualTo(CampaignStatus.RUNNING);
        clock.set(WED_11_IST.plus(Duration.ofDays(6)));
        logout(n2);
        Campaign paused = campaign(running.getId());
        assertThat(paused.getStatus()).isEqualTo(CampaignStatus.PAUSED);
        assertThat(paused.getPausedBy()).isEqualTo(PausedBy.SYSTEM);
        assertThat(paused.getPauseReason()).isEqualTo("TWO_LOGOUTS");
        assertThat(campaign(draft.getId()).getStatus()).isEqualTo(CampaignStatus.DRAFT);
        assertThat(campaign(byUser.getId()).getPausedBy()).isEqualTo(PausedBy.USER);
        assertThat(campaign(foreign.getId()).getStatus()).isEqualTo(CampaignStatus.RUNNING);
    }

    @Test
    void logoutsMoreThanAWeekApartPauseNothing() {
        Campaign running = campaign(brand, "run", CampaignStatus.RUNNING, null);
        logout(n1);
        clock.set(WED_11_IST.plus(Duration.ofDays(8)));
        logout(n2);
        assertThat(campaign(running.getId()).getStatus()).isEqualTo(CampaignStatus.RUNNING);
    }

    @Test
    void repeatedUnpairedIsOneLogout() {
        Campaign running = campaign(brand, "run", CampaignStatus.RUNNING, null);
        logout(n1);
        clock.set(WED_11_IST.plusSeconds(60));
        state(n1, "UNPAIRED_IDLE");
        logout(n1);
        assertThat(sender(n1).getLogoutAt()).isEqualTo(WED_11_IST);
        assertThat(campaign(running.getId()).getStatus()).isEqualTo(CampaignStatus.RUNNING);
    }

    @Test
    void otherStatesChangeNothing() {
        state(n1, "CONNECTED");
        state(n1, "OPENING");
        publish(n1, "ready", mapper.createObjectNode());
        CampaignSender s = sender(n1);
        assertThat(s.getState()).isEqualTo(SenderState.WARMING);
        assertThat(s.getLogoutAt()).isNull();
        assertThat(s.getRestUntil()).isNull();
        assertThat(sender(n2).getHalfPaceUntil()).isNull();
    }

    @Test
    void retiredSenderIgnoresLogout() {
        Campaign running = campaign(brand, "run", CampaignStatus.RUNNING, null);
        sender(n2);
        sender(n1, s -> s.setState(SenderState.RETIRED));
        logout(n1);
        publish(n1, "ready", mapper.createObjectNode());
        CampaignSender s1 = sender(n1);
        assertThat(s1.getState()).isEqualTo(SenderState.RETIRED);
        assertThat(s1.getLogoutAt()).isNull();
        assertThat(s1.getRestUntil()).isNull();
        assertThat(sender(n2).getHalfPaceUntil()).isNull();
        assertThat(campaign(running.getId()).getStatus()).isEqualTo(CampaignStatus.RUNNING);
    }
}
