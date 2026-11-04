package me.nageshk.omnichannel.service.automation;

import static org.assertj.core.api.Assertions.assertThat;

import me.nageshk.omnichannel.db.automation.AutomationEventKind;
import me.nageshk.omnichannel.db.automation.EventData;
import org.junit.jupiter.api.Test;

class EventSummariesTest {

    private static EventData message(String name, String phone, String text, String kind) {
        return new EventData(null, new EventData.MessageData("m1", text, kind, null), null,
                new EventData.ContactData("c1", name, phone, false, false), null, null, null, null);
    }

    private static EventData move(String from, String to) {
        return new EventData(null, null, null, null,
                new EventData.DealData("d1", "Big order", "OPEN", null, null, null, null), null,
                from == null ? null : new EventData.LaneData("s1", from, "OPEN"),
                new EventData.LaneData("s2", to, "WON"));
    }

    private static String of(EventData d) {
        return EventSummaries.of(d.deal() == null ? AutomationEventKind.MESSAGE_RECEIVED
                : AutomationEventKind.DEAL_LANE_CHANGED, d);
    }

    @Test
    void messageNamesTheSender() {
        assertThat(of(message("Asha", "919811100001", "Hello", "TEXT"))).isEqualTo("Message from Asha: Hello");
        assertThat(of(message(null, "919811100001", "Hello", "TEXT")))
                .isEqualTo("Message from +919811100001: Hello");
        assertThat(of(message(" ", null, "Hello", "TEXT"))).isEqualTo("Message from a contact: Hello");
    }

    @Test
    void longTextIsCutAndMediaIsNamed() {
        String text = "x".repeat(100);
        assertThat(of(message("Asha", null, text, "TEXT"))).isEqualTo("Message from Asha: " + "x".repeat(80) + "…");
        assertThat(of(message("Asha", null, "x".repeat(80), "TEXT"))).isEqualTo("Message from Asha: " + "x".repeat(80));
        assertThat(of(message("Asha", null, null, "PHOTO"))).isEqualTo("Message from Asha: Photo");
        assertThat(of(message("Asha", null, "", "AUDIO"))).isEqualTo("Message from Asha: Audio / voice note");
    }

    @Test
    void laneChangeNamesBothLanes() {
        assertThat(of(move("Proposal sent", "Won"))).isEqualTo("Big order moved from Proposal sent to Won");
        assertThat(of(move(null, "Won"))).isEqualTo("Big order moved from a removed lane to Won");
    }

    private static EventData adMessage(String headline) {
        return new EventData(null, new EventData.MessageData("m1", "Hi", "TEXT",
                new EventData.AdData("120", "https://fb.me/x", headline)), null,
                new EventData.ContactData("c1", "Asha", null, false, false), null, null, null, null);
    }

    @Test
    void adMessageNamesTheAd() {
        assertThat(of(adMessage("Diwali sale"))).isEqualTo("Message from Asha (from ad \u201cDiwali sale\u201d): Hi");
    }

    @Test
    void adWithoutHeadline() {
        assertThat(of(adMessage(null))).isEqualTo("Message from Asha (from an ad): Hi");
        assertThat(of(adMessage(" "))).isEqualTo("Message from Asha (from an ad): Hi");
    }
}
