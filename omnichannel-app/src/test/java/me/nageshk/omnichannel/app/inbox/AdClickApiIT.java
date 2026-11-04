package me.nageshk.omnichannel.app.inbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Map;
import me.nageshk.omnichannel.db.inbox.AdClick;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import me.nageshk.omnichannel.db.inbox.WaWebMessage;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** The inbox JSON carries a trimmed view of the click-to-WhatsApp ad, never the raw context. */
class AdClickApiIT extends InboxApiTestBase {

    private static AdClick ad() {
        return new AdClick("1200", "https://fb.me/1200", "FACEBOOK", "Diwali sale", "50% off", "FB_Ads", "click-1",
                T0, Map.of("title", "Diwali sale"));
    }

    @Test
    void threadShowsTheAd() throws Exception {
        WaWebChat c = chat("Alice", "911", 1, agent, null);
        WaWebMessage m = message(c, 10, "hi");
        m.setAdClick(ad());
        messages.save(m);

        JsonNode all = json(send(get(inbox() + "/chats/" + c.getId() + "/thread"), agentToken, null)
                .andExpect(status().isOk()));

        JsonNode adClick = all.get("items").get(0).get("message").get("adClick");
        assertThat(adClick.get("headline").asString()).isEqualTo("Diwali sale");
        assertThat(adClick.get("sourceId").asString()).isEqualTo("1200");
        assertThat(adClick.has("raw")).isFalse();
        assertThat(adClick.has("clickId")).isFalse();
        assertThat(adClick.has("conversionSource")).isFalse();
        assertThat(adClick.has("at")).isFalse();
    }

    @Test
    void chatListShowsTheAd() throws Exception {
        WaWebChat c = chat("Alice", "911", 1, agent, null);
        c.setAdClick(ad());
        chats.save(c);
        chat("Bob", "912", 2, agent, null);

        JsonNode page = json(send(get(chatsUrl()), agentToken, null).andExpect(status().isOk()));

        JsonNode withAd = null;
        JsonNode without = null;
        for (JsonNode i : page.get("items")) {
            if (i.get("id").asString().equals(c.getId())) withAd = i;
            else without = i;
        }
        assertThat(withAd.get("adClick").get("sourceId").asString()).isEqualTo("1200");
        assertThat(withAd.get("adClick").has("raw")).isFalse();
        assertThat(without.get("adClick").isNull()).isTrue();
    }
}
