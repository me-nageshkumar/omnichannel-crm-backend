package me.nageshk.omnichannel.service.channel.whatsapp;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.Optional;
import me.nageshk.omnichannel.common.channel.ChannelContext;
import me.nageshk.omnichannel.common.channel.ChannelKind;
import me.nageshk.omnichannel.common.channel.whatsapp.webhook.Message;
import me.nageshk.omnichannel.common.channel.whatsapp.webhook.Value;
import me.nageshk.omnichannel.common.channel.whatsapp.webhook.WebhookEvent;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class WaWebWebhookConverterTest {

    private static final ChannelContext CTX =
            new ChannelContext(ChannelKind.WHATSAPP_WEB, "cust", "ws", "pn-1", "919928088743");
    private static final Instant CAPTURED = Instant.parse("2026-11-05T10:00:00Z");

    private final ObjectMapper mapper = new ObjectMapper();
    private final WaWebWebhookConverter converter = new WaWebWebhookConverter();

    @ParameterizedTest
    @ValueSource(strings = {
            "text_cus", "text_lid_with_sender_pn", "text_lid_without_phone", "text_key_dollar1", "text_remote_string",
            "text_template", "text_link_preview", "image_caption", "image_view_once", "video_gif", "ptt", "document",
            "sticker", "location", "vcard", "multi_vcard", "reply_quoted", "forwarded", "ad_referral",
            "link_preview_not_ad", "notice_dropped", "group_dropped", "no_id_dropped", "message_event_ignored"})
    void convertsToTheExpectedWebhook(String name) throws IOException {
        String event = name.equals("message_event_ignored") ? "message" : "message_create";
        Optional<WebhookEvent> result = converter.convert(CTX, event, read(name + ".raw.json"), CAPTURED);
        JsonNode expected = readOrNull(name + ".expected.json");
        if (expected == null) {
            assertThat(result).isEmpty();
            return;
        }
        assertThat(result).isPresent();
        // through text, so that numbers take the same node types as the parsed expectation
        JsonNode actual = mapper.readTree(mapper.writeValueAsString(result.get()));
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void theTwoKeyFormsGiveTheSameId() throws IOException {
        Message a = convert("text_key_dollar1").messages().get(0);
        Message b = convert("text_cus").messages().get(0);
        assertThat(a.id()).isEqualTo(b.id());
    }

    @Test
    void timeFallsBackToTheCaptureTimeThenNow() throws IOException {
        ObjectNode raw = (ObjectNode) read("text_cus.raw.json");
        raw.remove("t");
        assertThat(convertRaw(raw, CAPTURED).messages().get(0).timestamp())
                .isEqualTo(String.valueOf(CAPTURED.getEpochSecond()));
        long before = Instant.now().getEpochSecond();
        long now = Long.parseLong(convertRaw(raw, null).messages().get(0).timestamp());
        assertThat(now).isBetween(before, before + 5);
    }

    @Test
    void aContactNamesThePersonOnce() throws IOException {
        Value v = convert("text_lid_with_sender_pn");
        assertThat(v.contacts()).singleElement().satisfies(c -> {
            assertThat(c.waId()).isEqualTo("919912345678");
            assertThat(c.userId()).isEqualTo("148073477075110");
            assertThat(c.profile().name()).isEqualTo("Asha");
        });
    }

    @Test
    void ourOwnMessageIsNotInboundHere() throws IOException {
        ObjectNode raw = (ObjectNode) read("text_cus.raw.json");
        raw.put("fromMe", true);
        assertThat(converter.convert(CTX, "message_create", raw, CAPTURED)).isEmpty();
    }

    @Test
    void chatIdsAndPersonChats() throws IOException {
        assertThat(WaWebWebhookConverter.chatIdOf(read("text_remote_string.raw.json")))
                .isEqualTo("919912345678@c.us");
        assertThat(WaWebWebhookConverter.isPersonChat("919912345678@c.us")).isTrue();
        assertThat(WaWebWebhookConverter.isPersonChat("148073477075110@lid")).isTrue();
        assertThat(WaWebWebhookConverter.isPersonChat("1203@g.us")).isFalse();
    }

    private Value convert(String name) throws IOException {
        return convertRaw(read(name + ".raw.json"), CAPTURED);
    }

    private Value convertRaw(JsonNode raw, Instant capturedAt) {
        WebhookEvent e = converter.convert(CTX, "message_create", raw, capturedAt).orElseThrow();
        return e.entry().get(0).changes().get(0).value();
    }

    private JsonNode read(String file) throws IOException {
        return readOrNull(file);
    }

    private JsonNode readOrNull(String file) throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/wawebhook/" + file)) {
            return in == null ? null : mapper.readTree(in);
        }
    }
}
