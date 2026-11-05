package me.nageshk.omnichannel.service.channel.whatsapp;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import me.nageshk.omnichannel.common.channel.ChannelContext;
import me.nageshk.omnichannel.common.channel.ChannelKind;
import me.nageshk.omnichannel.common.channel.whatsapp.webhook.Change;
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
            "link_preview_not_ad", "notice_dropped", "group_dropped", "no_id_dropped", "message_event_ignored", "echo_text_ack0", "echo_text_ack_failed",
            "echo_image_ack2", "echo_lid_unknown_phone", "ack_read_ours", "ack_failed_ours", "ack_inbound_dropped",
            "ack_pending_dropped",
            "poll_creation_single", "poll_creation_multi_with_votes", "vote_inbound", "vote_ours", "call_missed",
            "call_rejected", "call_completed_video", "revoked_sender", "biz_placeholder_auth", "newsletter_invite",
            "album", "unknown_phone_only", "interactive_quick_reply_with_video", "interactive_cta_url",
            "interactive_caption_only", "interactive_media_body_only",
            "buttons_response", "list_response", "rich_response_text", "order_minimal",
            "unknown_type_falls_to_unsupported"})
    void convertsToTheExpectedWebhook(String name) throws IOException {
        String event = name.startsWith("ack_") ? "message_ack"
                : name.startsWith("vote_") ? "vote_update"
                : name.equals("message_event_ignored") ? "message" : "message_create";
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
    void aVoteIsAMessageAboutThePollWithADeterministicId() throws IOException {
        Value v = converter.convert(CTX, "vote_update", read("vote_inbound.raw.json"), CAPTURED).orElseThrow()
                .entry().get(0).changes().get(0).value();
        Message vote = v.messages().get(0);
        assertThat(vote.type()).isEqualTo("poll_vote");
        assertThat(vote.id()).isEqualTo("vote:true_919912345678@c.us_POLL1:919912345678@c.us");
        assertThat(vote.pollVote().selectedOptions()).containsExactly(0, 2);
        assertThat(vote.waWeb().chatId()).isEqualTo("919912345678@c.us");
        assertThat(v.contacts()).isNull();
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
        assertThat(converter.message(CTX, raw, CAPTURED)).isEmpty();
    }

    @Test
    void anEchoWithAnAckCarriesItsStatusInTheSameEntry() throws IOException {
        WebhookEvent e = converter.convert(CTX, "message_create", read("echo_image_ack2.raw.json"), CAPTURED)
                .orElseThrow();
        List<Change> changes = e.entry().get(0).changes();
        assertThat(changes).extracting(Change::field).containsExactly("smb_message_echoes", "messages");
        assertThat(changes.get(1).value().statuses().get(0).id())
                .isEqualTo(changes.get(0).value().messageEchoes().get(0).id());
    }

    @Test
    void anEchoToAnUnknownLidTakesTheDigitsWhenGiven() throws IOException {
        var echo = converter.echo(CTX, read("echo_lid_unknown_phone.raw.json"), CAPTURED, "919912345678");
        assertThat(echo.orElseThrow().to()).isEqualTo("919912345678");
        assertThat(converter.echo(CTX, read("text_cus.raw.json"), CAPTURED, null)).isEmpty();
    }

    @Test
    void anAckMapsToMetasStatuses() {
        assertThat(WaWebWebhookConverter.statusOf("i", 1, CAPTURED, "1@c.us", "1").status()).isEqualTo("sent");
        assertThat(WaWebWebhookConverter.statusOf("i", 2, CAPTURED, "1@c.us", "1").status()).isEqualTo("delivered");
        assertThat(WaWebWebhookConverter.statusOf("i", 4, CAPTURED, "1@c.us", "1").status()).isEqualTo("played");
        assertThat(WaWebWebhookConverter.statusOf("i", 0, CAPTURED, "1@c.us", "1")).isNull();
        assertThat(WaWebWebhookConverter.statusOf("i", 7, CAPTURED, "1@c.us", "1")).isNull();
    }

    @Test
    void chatIdsAndPersonChats() throws IOException {
        assertThat(WaWebWebhookConverter.chatIdOf(read("text_remote_string.raw.json")))
                .isEqualTo("919912345678@c.us");
        assertThat(WaWebWebhookConverter.isPersonChat("919912345678@c.us")).isTrue();
        assertThat(WaWebWebhookConverter.isPersonChat("148073477075110@lid")).isTrue();
        assertThat(WaWebWebhookConverter.isPersonChat("1203@g.us")).isFalse();
    }

    private ObjectNode text() throws IOException {
        return (ObjectNode) read("text_cus.raw.json");
    }

    @Test
    @SuppressWarnings("unchecked")
    void adContextIsKeptWithoutImagesDottedKeysAndDollarKeys() throws IOException {
        ObjectNode raw = text();
        ObjectNode ad = raw.putObject("ctwaContext");
        ad.put("sourceId", "1").put("mediaUrl", "u").put("originalImageUrl", "o").put("thumbnailUrl", "t")
                .put("keep", "yes");
        ad.putObject("nested").put("a.b", 1).put("$x", 2).put("ok", 3);
        java.util.Map<String, Object> kept = convertRaw(raw, CAPTURED).messages().get(0).waWeb().ctwaContext();
        assertThat(kept).containsOnlyKeys("sourceId", "keep", "nested");
        assertThat(((java.util.Map<String, Object>) kept.get("nested")).keySet()).containsExactly("ok");
    }

    @Test
    void adContextOver4KbIsDroppedButTheReferralStays() throws IOException {
        ObjectNode raw = text();
        raw.putObject("ctwaContext").put("sourceId", "1").put("big", "x".repeat(5000));
        Message m = convertRaw(raw, CAPTURED).messages().get(0);
        assertThat(m.referral().sourceId()).isEqualTo("1");
        assertThat(m.waWeb().ctwaContext()).isNull();
    }

    @Test
    void theKeyIsRebuiltWhenItHasNeitherSerializedNorDollar1() throws IOException {
        ObjectNode raw = text();
        ObjectNode id = (ObjectNode) raw.get("id");
        id.remove("_serialized");
        assertThat(convertRaw(raw, CAPTURED).messages().get(0).id()).isEqualTo("false_919912345678@c.us_3EB0ABC");
    }

    @Test
    void timeReadsTheTimestampFieldWhenThereIsNoT() throws IOException {
        ObjectNode raw = text();
        raw.remove("t");
        raw.put("timestamp", 1700000000L);
        assertThat(convertRaw(raw, CAPTURED).messages().get(0).timestamp()).isEqualTo("1700000000");
    }

    @Test
    void theNameComesFromDataNotifyNameThenPushname() throws IOException {
        ObjectNode raw = text();
        raw.remove("notifyName");
        raw.putObject("_data").put("notifyName", "Data Name");
        raw.put("pushname", "Push");
        assertThat(convertRaw(raw, CAPTURED).contacts().get(0).profile().name()).isEqualTo("Data Name");
        raw.remove("_data");
        assertThat(convertRaw(raw, CAPTURED).contacts().get(0).profile().name()).isEqualTo("Push");
    }

    @Test
    void hasMediaWithoutDirectPathMakesMediaAvailable() throws IOException {
        ObjectNode raw = (ObjectNode) read("image_view_once.raw.json");
        raw.put("hasMedia", true);
        assertThat(convertRaw(raw, CAPTURED).messages().get(0).waWeb().mediaAvailable()).isTrue();
    }

    @Test
    void aLowForwardingScoreIsForwardedButNotFrequently() throws IOException {
        ObjectNode raw = text();
        raw.put("isForwarded", true).put("forwardingScore", 4);
        Message m = convertRaw(raw, CAPTURED).messages().get(0);
        assertThat(m.context().forwarded()).isTrue();
        assertThat(m.context().frequentlyForwarded()).isNull();
    }

    @Test
    void aSourceUrlAloneIsALinkPreviewNotAnAd() throws IOException {
        // seen live 2026-11-05: a business's preview of a group invite carries a ctwaContext with no ad identity
        ObjectNode raw = text();
        raw.putObject("ctwaContext").put("sourceUrl", "https://chat.whatsapp.com/GQ4yn")
                .put("title", "Motilal Oswal Group")
                .put("description", "Motilal Oswal Group").put("mediaType", 1).put("thumbnail", "AAAA");
        Message m = convertRaw(raw, CAPTURED).messages().get(0);
        assertThat(m.referral()).isNull();
        assertThat(m.waWeb().ctwaContext()).isNull();
    }

    @Test
    void textWithNoPlaceInMetasShapeGoesToWaWebBody() {
        ObjectNode raw = mapper.createObjectNode();
        raw.putObject("id").put("fromMe", false).put("remote", "919912345678@c.us").put("id", "P1")
                .put("_serialized", "false_919912345678@c.us_P1");
        raw.put("type", "product").put("t", 1793872800L).put("directPath", "/v/t62/abc")
                .put("body", "/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRof".repeat(4))
                .put("caption", "Blue kurta");
        Message m = convertRaw(raw, CAPTURED).messages().get(0);
        assertThat(m.type()).isEqualTo("unsupported");
        assertThat(m.waWeb().body()).isEqualTo("Blue kurta");
        raw.remove("caption");
        assertThat(convertRaw(raw, CAPTURED).messages().get(0).waWeb().body()).isNull();
        // a type Meta has a text for keeps wa_web.body empty
        assertThat(convertRaw(raw.put("type", "chat").put("body", "hi"), CAPTURED).messages().get(0).waWeb().body())
                .isNull();
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
