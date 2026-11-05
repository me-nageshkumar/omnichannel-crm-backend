package me.nageshk.omnichannel.service.inbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import me.nageshk.omnichannel.common.channel.ChannelContext;
import me.nageshk.omnichannel.common.channel.ChannelKind;
import me.nageshk.omnichannel.common.channel.whatsapp.webhook.Message;
import me.nageshk.omnichannel.common.channel.whatsapp.webhook.MessageEcho;
import me.nageshk.omnichannel.db.inbox.AdClick;
import me.nageshk.omnichannel.db.inbox.WaWebMessage;
import me.nageshk.omnichannel.service.channel.whatsapp.WaWebWebhookConverter;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * What the store reads of a raw WhatsApp Web model: the model goes through {@link WaWebWebhookConverter} and the
 * stored fields are read back by {@link MessageFields}, as the live path and the history import do.
 */
class MessageFieldsTest {

    /** What WhatsApp puts in {@code body} of a media message: a base64 JPEG thumbnail. */
    private static final String THUMBNAIL = "/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRof".repeat(10);
    private static final ChannelContext CTX =
            new ChannelContext(ChannelKind.WHATSAPP_WEB, "cust", "ws", "pn-1", "919928088743");
    private static final Instant AT = Instant.parse("2026-11-04T10:00:00Z");
    private static final String CHAT = "919912345678@c.us";

    private static final ObjectMapper JSON = new ObjectMapper();
    private final WaWebWebhookConverter converter = new WaWebWebhookConverter();

    /** A person's raw message of {@code type} in a phone chat. */
    private static ObjectNode raw(String type) {
        ObjectNode p = JSON.createObjectNode();
        p.putObject("id").put("fromMe", false).put("remote", CHAT).put("id", "K1").put("_serialized",
                "false_" + CHAT + "_K1");
        return p.put("fromMe", false).put("type", type).put("t", 1_700_000_000L);
    }

    private Optional<Message> convert(ObjectNode raw) {
        return converter.message(CTX, raw, AT);
    }

    private MessageFields.Content content(ObjectNode raw) {
        return MessageFields.of(convert(raw).orElseThrow());
    }

    private AdClick ad(ObjectNode raw) {
        return MessageFields.adClickOf(convert(raw).orElseThrow(), AT);
    }

    // ---- text and cards

    @Test
    void aChannelInviteIsTheNameOfTheChannel() {
        ObjectNode p = raw("newsletter_follower_invite");
        p.putObject("newsletterFollowerInviteInfo").put("newsletterName", "Speak English")
                .put("inviteMessage", "Invitation to follow the channel “Speak English”");
        assertThat(MessageFields.bodyOf(content(p))).isEqualTo("Speak English");
    }

    @Test
    void aChannelNameOnAnotherTypeIsIgnored() {
        ObjectNode p = raw("chat").put("body", "hi");
        p.putObject("newsletterFollowerInviteInfo").put("newsletterName", "Speak English");
        assertThat(MessageFields.bodyOf(content(p))).isEqualTo("hi");
    }

    @Test
    void theBusinessTagIsWhatsAppsTemplateTag() {
        assertThat(MessageFields.businessTagOf(content(raw("biz_content_placeholder").put("hsmTag", "AUTHENTICATION"))))
                .isEqualTo("AUTHENTICATION");
        assertThat(MessageFields.businessTagOf(content(raw("chat").put("body", "hi")))).isNull();
    }

    @Test
    void severalContactCardsAreTheBodyOneAfterTheOther() {
        ObjectNode p = raw("multi_vcard");
        p.putArray("vcardList").add(JSON.createObjectNode().put("displayName", "A").put("vcard", "CARD A"))
                .add(JSON.createObjectNode().put("displayName", "B").put("vcard", "CARD B"));
        MessageFields.Content c = content(p);
        assertThat(MessageFields.bodyOf(c)).isEqualTo("CARD A\nCARD B");
        assertThat(MessageFields.storedType(c)).isEqualTo("multi_vcard");
    }

    @Test
    void aContactCardIsItsBody() {
        MessageFields.Content c = content(raw("vcard").put("body", "BEGIN:VCARD\nFN:A\nEND:VCARD"));
        assertThat(MessageFields.bodyOf(c)).isEqualTo("BEGIN:VCARD\nFN:A\nEND:VCARD");
        assertThat(MessageFields.storedType(c)).isEqualTo("vcard");
    }

    @Test
    void textMessageKeepsWhatWasTyped() {
        assertThat(MessageFields.bodyOf(content(raw("chat").put("body", THUMBNAIL)))).isEqualTo(THUMBNAIL);
        assertThat(MessageFields.bodyOf(content(raw("chat").put("body", "hi").put("caption", "ignored"))))
                .isEqualTo("hi");
    }

    // ---- notices

    @Test
    void whatsAppsOwnNoticesAreDropped() {
        for (String type : new String[] {"notification_template", "e2e_notification", "notification",
                "group_notification", "broadcast_notification"}) {
            assertThat(WaWebWebhookConverter.isNoticeType(type)).as(type).isTrue();
            assertThat(convert(raw(type))).as(type).isEmpty();
        }
        assertThat(WaWebWebhookConverter.isNoticeType(null)).isFalse();
    }

    @Test
    void aFlagAloneDropsNothing() {
        assertThat(convert(raw("something_new").put("isNotification", true))).isPresent();
        assertThat(convert(raw("chat").put("body", "hi").put("isNotification", true))).isPresent();
    }

    @Test
    void whatPeopleSendIsNoNotice() {
        for (String type : new String[] {"chat", "image", "call_log", "poll_creation", "location", "revoked"}) {
            assertThat(WaWebWebhookConverter.isNoticeType(type)).as(type).isFalse();
            assertThat(convert(raw(type))).as(type).isPresent();
        }
    }

    // ---- calls

    @Test
    void onlyACallLogHasACall() {
        assertThat(MessageFields.callOf(content(raw("chat").put("body", "hi").put("duration", 30)))).isNull();
    }

    @Test
    void aCallLogSayingNothingIsStillACall() {
        assertThat(MessageFields.callOf(content(raw("call_log")))).isEqualTo(new WaWebMessage.Call(false, false, null));
    }

    @Test
    void aSubtypeSaysNothingOfACall() {
        // a real call_log has no subtype (seen on a phone, 2026-11-02): it is not read
        assertThat(MessageFields.callOf(content(raw("call_log").put("subtype", "miss_video"))))
                .isEqualTo(new WaWebMessage.Call(false, false, null));
    }

    @Test
    void aCallCanSayItIsVideoAndHowLongItRan() {
        assertThat(MessageFields.callOf(content(raw("call_log").put("isVideoCall", true).put("callDuration", 125))))
                .isEqualTo(new WaWebMessage.Call(true, false, 125));
        assertThat(MessageFields.callOf(content(raw("call_log").put("duration", 30))))
                .isEqualTo(new WaWebMessage.Call(false, false, 30));
    }

    @Test
    void aMissedOrRejectedOutcomeIsMissed() {
        assertThat(MessageFields.callOf(content(raw("call_log").put("callOutcome", "MISSED"))))
                .isEqualTo(new WaWebMessage.Call(false, true, null));
        assertThat(MessageFields.callOf(content(raw("call_log").put("callOutcome", "REJECTED"))))
                .isEqualTo(new WaWebMessage.Call(false, true, null));
    }

    @Test
    void aDurationOfZeroIsNoDuration() {
        assertThat(MessageFields.callOf(content(raw("call_log").put("duration", 0))))
                .isEqualTo(new WaWebMessage.Call(false, false, null));
    }

    // ---- media text and media

    @Test
    void mediaTextIsTheCaption() {
        assertThat(MessageFields.bodyOf(content(raw("image").put("body", THUMBNAIL).put("caption", "Look at this"))))
                .isEqualTo("Look at this");
    }

    @Test
    void thumbnailWithoutCaptionIsNoText() {
        assertThat(MessageFields.bodyOf(content(raw("image").put("body", THUMBNAIL)))).isNull();
        assertThat(MessageFields.bodyOf(content(raw("video").put("body", THUMBNAIL)))).isNull();
    }

    @Test
    void readableMediaBodyIsKept() {
        assertThat(MessageFields.bodyOf(content(raw("image").put("body", "Is this the one?"))))
                .isEqualTo("Is this the one?");
    }

    @Test
    void longTokenInACaptionIsNotAThumbnail() {
        String hash = "a3f9".repeat(16);
        assertThat(MessageFields.bodyOf(content(raw("document").put("caption", hash)))).isEqualTo(hash);
    }

    @Test
    void aProductIsUnsupportedAndKeepsNoThumbnail() {
        // not one of Meta's types: kept as unsupported under its own name, with no text (a caption is not carried)
        MessageFields.Content c = content(raw("product").put("directPath", "/v/t62/abc").put("body", THUMBNAIL)
                .put("caption", "Blue kurta"));
        assertThat(MessageFields.storedType(c)).isEqualTo("product");
        assertThat(MessageFields.bodyOf(c)).isNull();
    }

    @Test
    void directPathMeansTheMessageHasMedia() {
        WaWebMessage.Media media = MessageFields.mediaOf(content(raw("image").put("directPath", "/v/t62/abc")
                .put("mimetype", "image/jpeg").put("size", 2048)));
        assertThat(media.mimetype()).isEqualTo("image/jpeg");
        assertThat(media.size()).isEqualTo(2048L);
    }

    @Test
    void noDirectPathAndNoFlagMeansNoMedia() {
        assertThat(MessageFields.mediaOf(content(raw("chat").put("body", "hi")))).isNull();
        assertThat(MessageFields.mediaOf(content(raw("image").put("directPath", "")))).isNull();
    }

    // ---- time

    @Test
    void timeIsMetasSecondsElseTheFallback() {
        Instant fallback = Instant.parse("2030-01-01T00:00:00Z");
        assertThat(MessageFields.timestampOf("1700000000", fallback)).isEqualTo(Instant.ofEpochSecond(1_700_000_000L));
        assertThat(MessageFields.timestampOf(null, fallback)).isEqualTo(fallback);
        assertThat(MessageFields.timestampOf("0", fallback)).isEqualTo(fallback);
        assertThat(MessageFields.timestampOf("soon", fallback)).isEqualTo(fallback);
    }

    // ---- location

    @Test
    void locationIsReadFromTheRawModel() {
        WaWebMessage.Location location = MessageFields.locationOf(content(raw("location").put("lat", 12.5)
                .put("lng", 77.25).put("loc", "Blue Cafe\nMG Road")));
        assertThat(location.latitude()).isEqualTo(12.5);
        assertThat(location.longitude()).isEqualTo(77.25);
        assertThat(location.description()).isEqualTo("Blue Cafe\nMG Road");
    }

    @Test
    void locationWithoutAPlaceNameHasNoDescription() {
        assertThat(MessageFields.locationOf(content(raw("location").put("lat", 12.5).put("lng", 77.25))).description())
                .isNull();
    }

    @Test
    void locationAtZeroIsStillALocation() {
        assertThat(MessageFields.locationOf(content(raw("location").put("lat", 0.0).put("lng", 0.0)))).isNotNull();
    }

    @Test
    void messageWithoutCoordinatesHasNoLocation() {
        assertThat(MessageFields.locationOf(content(raw("location")))).isNull();
    }

    @Test
    void coordinatesOnAnotherTypeAreNotALocation() {
        assertThat(MessageFields.locationOf(content(raw("chat").put("body", "hi").put("lat", 12.5).put("lng", 77.25))))
                .isNull();
    }

    // ---- polls

    @Test
    void pollQuestionIsTheRawPollName() {
        assertThat(MessageFields.bodyOf(content(raw("poll_creation").put("pollName", "Lunch where?"))))
                .isEqualTo("Lunch where?");
        assertThat(MessageFields.bodyOf(content(raw("poll_creation").put("body", "").put("pollName", "Lunch where?"))))
                .isEqualTo("Lunch where?");
    }

    @Test
    void pollNameIsIgnoredOnOtherTypes() {
        assertThat(MessageFields.bodyOf(content(raw("chat").put("body", "").put("pollName", "ignored")))).isEmpty();
    }

    @Test
    void pollOptionsAreReadFromRawObjects() {
        ObjectNode p = raw("poll_creation");
        p.putArray("pollOptions").add(p.objectNode().put("name", "Pizza").put("localId", 0))
                .add(p.objectNode().put("name", "Sushi").put("localId", 1));
        assertThat(MessageFields.pollOptionsOf(content(p))).containsExactly("Pizza", "Sushi");
    }

    @Test
    void pollOptionsMayBePlainStrings() {
        ObjectNode p = raw("poll_creation");
        p.putArray("pollOptions").add("Pizza").add("Sushi");
        assertThat(MessageFields.pollOptionsOf(content(p))).containsExactly("Pizza", "Sushi");
    }

    @Test
    void optionsOnAnotherTypeAreNotAPoll() {
        ObjectNode p = raw("chat").put("body", "hi");
        p.putArray("pollOptions").add("Pizza");
        assertThat(MessageFields.pollOptionsOf(content(p))).isNull();
    }

    @Test
    void messageWithoutOptionsHasNone() {
        assertThat(MessageFields.pollOptionsOf(content(raw("poll_creation")))).isNull();
        ObjectNode blank = raw("poll_creation");
        blank.putArray("pollOptions").add(" ");
        assertThat(MessageFields.pollOptionsOf(content(blank))).isNull();
    }

    @Test
    void aFetchedPollsVotesSayWhichAreOurs() {
        ObjectNode p = raw("poll_creation").put("pollName", "Lunch?");
        p.putArray("pollOptions").addObject().put("name", "Yes").put("localId", 0);
        var votes = p.putArray("pollVotes");
        votes.addObject().put("voter", "1@lid").put("fromMe", false).put("at", 5000L).putArray("options").add(0);
        votes.addObject().put("voter", "2@c.us").put("fromMe", true).put("at", 6000L).putArray("options").add(0);
        assertThat(MessageFields.pollVotesOf(content(p))).containsExactly(
                new WaWebMessage.PollVote("1@lid", false, List.of(0), Instant.ofEpochMilli(5000)),
                new WaWebMessage.PollVote("2@c.us", true, List.of(0), Instant.ofEpochMilli(6000)));
    }

    // ---- the ad behind a message

    @Test
    void adClickReadsTheWaJsShape() {
        ObjectNode p = raw("chat").put("body", "hi");
        p.putObject("ctwaContext").put("sourceUrl", "https://fb.me/x").put("sourceId", "1200")
                .put("sourceApp", "facebook").put("title", "Diwali sale").put("description", "20% off")
                .put("conversionSource", "FB_Ads").put("thumbnail", "AAAA");
        AdClick ad = ad(p);
        assertThat(ad.sourceUrl()).isEqualTo("https://fb.me/x");
        assertThat(ad.sourceId()).isEqualTo("1200");
        assertThat(ad.sourceApp()).isEqualTo("facebook");
        assertThat(ad.headline()).isEqualTo("Diwali sale");
        assertThat(ad.description()).isEqualTo("20% off");
        assertThat(ad.conversionSource()).isEqualTo("FB_Ads");
        assertThat(ad.at()).isEqualTo(AT);
        assertThat(ad.raw()).containsEntry("sourceUrl", "https://fb.me/x").doesNotContainKey("thumbnail");
    }

    @Test
    void clickIdAliases() {
        ObjectNode p = raw("chat").put("body", "hi");
        p.putObject("ctwaContext").put("sourceId", "1").put("ctwa_clid", "CLK");
        assertThat(ad(p).clickId()).isEqualTo("CLK");
        ((ObjectNode) p.get("ctwaContext")).put("ctwaClid", "PREFERRED");
        assertThat(ad(p).clickId()).isEqualTo("PREFERRED");
    }

    @Test
    void headlineFallsBackToHeadline() {
        ObjectNode p = raw("chat").put("body", "hi");
        p.putObject("ctwaContext").put("sourceId", "1").put("headline", "Fallback");
        assertThat(ad(p).headline()).isEqualTo("Fallback");
    }

    @Test
    void anAdNeedsASourceIdAConversionSourceOrAClickId() {
        ObjectNode p = raw("chat").put("body", "hi");
        assertThat(ad(p)).isNull();
        p.putObject("ctwaContext");
        assertThat(ad(p)).isNull();
        p.putObject("ctwaContext").put("sourceUrl", "  ").put("sourceId", "").put("title", "only a title");
        assertThat(ad(p)).isNull();
        // the 2026-11-05 rule: a link preview has a source url and is no ad
        p.putObject("ctwaContext").put("sourceUrl", "https://fb.me/x").put("title", "a shared link");
        assertThat(ad(p)).isNull();
        p.putObject("ctwaContext").put("conversionSource", "FB_Ads");
        assertThat(ad(p)).isNotNull();
        p.putObject("ctwaContext").put("ctwaClid", "CLK");
        assertThat(ad(p)).isNotNull();
    }

    @Test
    void outboundContextIsIgnored() {
        ObjectNode p = raw("chat").put("body", "hi").put("fromMe", true);
        ((ObjectNode) p.get("id")).put("fromMe", true).put("_serialized", "true_" + CHAT + "_K1");
        p.putObject("ctwaContext").put("sourceId", "1200");
        // our own message is an echo: Meta's echo has no referral, so there is no ad to read from it
        assertThat(convert(p)).isEmpty();
        MessageEcho echo = converter.echo(CTX, p, AT, null).orElseThrow();
        assertThat(echo.id()).isEqualTo("true_" + CHAT + "_K1");
    }

    @Test
    void longTextIsCut() {
        ObjectNode p = raw("chat").put("body", "hi");
        p.putObject("ctwaContext").put("sourceId", "1").put("title", "x".repeat(600));
        assertThat(ad(p).headline()).hasSize(500);
    }

    @Test
    void oversizeRawIsDropped() {
        ObjectNode p = raw("chat").put("body", "hi");
        p.putObject("ctwaContext").put("sourceId", "1").put("description", "d".repeat(5 * 1024));
        AdClick ad = ad(p);
        assertThat(ad.raw()).isNull();
        assertThat(ad.sourceId()).isEqualTo("1");
        assertThat(ad.description()).hasSize(500);
    }

    @Test
    void rawDropsKeysMongoRefuses() {
        ObjectNode p = raw("chat").put("body", "hi");
        p.putObject("ctwaContext").put("sourceId", "1").put("a.b", "x").put("$c", "y").put("ok", "z");
        assertThat(ad(p).raw()).containsOnlyKeys("ok", "sourceId");
    }

    @Test
    void numericSourceIdCounts() {
        ObjectNode p = raw("chat").put("body", "hi");
        p.putObject("ctwaContext").put("sourceId", 1200L).put("conversionSource", 7);
        AdClick ad = ad(p);
        assertThat(ad.sourceId()).isEqualTo("1200");
        assertThat(ad.conversionSource()).isEqualTo("7");
    }
}
