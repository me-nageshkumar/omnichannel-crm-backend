package me.nageshk.omnichannel.service.inbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import me.nageshk.omnichannel.db.inbox.AdClick;
import me.nageshk.omnichannel.db.inbox.WaWebMessage;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class MessagePayloadsTest {

    /** What WhatsApp puts in {@code body} of a media message: a base64 JPEG thumbnail. */
    private static final String THUMBNAIL = "/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRof".repeat(10);

    private static ObjectNode message(String type) {
        return new ObjectMapper().createObjectNode().put("type", type);
    }

    @Test
    void aChannelInviteIsTheNameOfTheChannel() {
        ObjectNode p = message("newsletter_follower_invite");
        p.putObject("newsletterFollowerInviteInfo").put("newsletterName", "Speak English")
                .put("inviteMessage", "Invitation to follow the channel “Speak English”");
        assertThat(MessagePayloads.body(p)).isEqualTo("Speak English");
    }

    @Test
    void aChannelNameOnAnotherTypeIsIgnored() {
        ObjectNode p = message("chat").put("body", "hi");
        p.putObject("newsletterFollowerInviteInfo").put("newsletterName", "Speak English");
        assertThat(MessagePayloads.body(p)).isEqualTo("hi");
    }

    @Test
    void theBusinessTagIsWhatsAppsTemplateTag() {
        assertThat(MessagePayloads.businessTag(message("biz_content_placeholder").put("hsmTag", "AUTHENTICATION")))
                .isEqualTo("AUTHENTICATION");
        assertThat(MessagePayloads.businessTag(message("chat"))).isNull();
    }

    @Test
    void severalContactCardsAreTheBodyOneAfterTheOther() {
        ObjectNode p = message("multi_vcard");
        p.putArray("vcardList").add(new ObjectMapper().createObjectNode().put("displayName", "A").put("vcard", "CARD A"))
                .add(new ObjectMapper().createObjectNode().put("displayName", "B").put("vcard", "CARD B"));
        assertThat(MessagePayloads.body(p)).isEqualTo("CARD A\nCARD B");
    }

    @Test
    void aContactCardIsItsBody() {
        assertThat(MessagePayloads.body(message("vcard").put("body", "BEGIN:VCARD\nFN:A\nEND:VCARD")))
                .isEqualTo("BEGIN:VCARD\nFN:A\nEND:VCARD");
    }

    @Test
    void whatsAppsOwnNoticesAreNotices() {
        assertThat(MessagePayloads.isNotice(message("notification_template"))).isTrue();
        assertThat(MessagePayloads.isNotice(message("e2e_notification"))).isTrue();
        assertThat(MessagePayloads.isNotice(message("notification"))).isTrue();
        assertThat(MessagePayloads.isNotice(message("group_notification"))).isTrue();
        assertThat(MessagePayloads.isNotice(message("broadcast_notification"))).isTrue();
    }

    @Test
    void aFlagAloneDropsNothing() {
        assertThat(MessagePayloads.isNotice(message("something_new").put("isNotification", true))).isFalse();
        assertThat(MessagePayloads.isNotice(message("chat").put("isNotification", true))).isFalse();
    }

    @Test
    void whatPeopleSendIsNoNotice() {
        for (String type : new String[] {"chat", "image", "call_log", "poll_creation", "location", "revoked"}) {
            assertThat(MessagePayloads.isNotice(message(type))).isFalse();
        }
    }

    @Test
    void onlyACallLogHasACall() {
        assertThat(MessagePayloads.call(message("chat").put("duration", 30))).isNull();
    }

    @Test
    void aCallLogSayingNothingIsStillACall() {
        assertThat(MessagePayloads.call(message("call_log"))).isEqualTo(new WaWebMessage.Call(false, false, null));
    }

    @Test
    void aSubtypeSaysNothingOfACall() {
        // a real call_log has no subtype (seen on a phone, 2026-11-02): it is not read
        assertThat(MessagePayloads.call(message("call_log").put("subtype", "miss_video")))
                .isEqualTo(new WaWebMessage.Call(false, false, null));
    }

    @Test
    void aCallCanSayItIsVideoAndHowLongItRan() {
        assertThat(MessagePayloads.call(message("call_log").put("isVideoCall", true).put("callDuration", 125)))
                .isEqualTo(new WaWebMessage.Call(true, false, 125));
        assertThat(MessagePayloads.call(message("call_log").put("duration", 30)))
                .isEqualTo(new WaWebMessage.Call(false, false, 30));
    }

    @Test
    void aMissedOutcomeIsMissed() {
        assertThat(MessagePayloads.call(message("call_log").put("callOutcome", "MISSED")))
                .isEqualTo(new WaWebMessage.Call(false, true, null));
    }

    @Test
    void aDurationOfZeroIsNoDuration() {
        assertThat(MessagePayloads.call(message("call_log").put("duration", 0)))
                .isEqualTo(new WaWebMessage.Call(false, false, null));
    }

    @Test
    void mediaTextIsTheCaption() {
        assertThat(MessagePayloads.body(message("image").put("body", THUMBNAIL).put("caption", "Look at this")))
                .isEqualTo("Look at this");
    }

    @Test
    void thumbnailWithoutCaptionIsNoText() {
        assertThat(MessagePayloads.body(message("image").put("body", THUMBNAIL))).isNull();
        assertThat(MessagePayloads.body(message("video").put("body", THUMBNAIL))).isNull();
    }

    @Test
    void readableMediaBodyIsKept() {
        assertThat(MessagePayloads.body(message("image").put("body", "Is this the one?")))
                .isEqualTo("Is this the one?");
    }

    @Test
    void textMessageKeepsWhatWasTyped() {
        assertThat(MessagePayloads.body(message("chat").put("body", THUMBNAIL))).isEqualTo(THUMBNAIL);
        assertThat(MessagePayloads.body(message("chat").put("body", "hi").put("caption", "ignored"))).isEqualTo("hi");
    }

    @Test
    void anyMediaTypeUsesTheCaptionAndDropsTheThumbnail() {
        ObjectNode product = message("product").put("directPath", "/v/t62/abc").put("body", THUMBNAIL);
        assertThat(MessagePayloads.body(product)).isNull();
        assertThat(MessagePayloads.body(product.put("caption", "Blue kurta"))).isEqualTo("Blue kurta");
    }

    @Test
    void longTokenInACaptionIsNotAThumbnail() {
        String hash = "a3f9".repeat(16);
        assertThat(MessagePayloads.body(message("document").put("caption", hash))).isEqualTo(hash);
        assertThat(MessageBodies.readable("document", hash)).isEqualTo(hash);
    }

    @Test
    void timeIsWhatsAppsRawField() {
        Instant fallback = Instant.parse("2030-01-01T00:00:00Z");
        assertThat(MessagePayloads.timestamp(message("chat").put("t", 1_700_000_000L), fallback))
                .isEqualTo(Instant.ofEpochSecond(1_700_000_000L));
    }

    @Test
    void timeFallsBackOnlyWhenThePayloadHasNone() {
        Instant fallback = Instant.parse("2030-01-01T00:00:00Z");
        assertThat(MessagePayloads.timestamp(message("chat"), fallback)).isEqualTo(fallback);
        assertThat(MessagePayloads.timestamp(message("chat").put("timestamp", 1_700_000_000L), fallback))
                .isEqualTo(Instant.ofEpochSecond(1_700_000_000L));
    }

    @Test
    void locationIsReadFromTheRawModel() {
        var location = MessagePayloads.location(message("location").put("lat", 12.5).put("lng", 77.25)
                .put("loc", "Blue Cafe\nMG Road"));
        assertThat(location.latitude()).isEqualTo(12.5);
        assertThat(location.longitude()).isEqualTo(77.25);
        assertThat(location.description()).isEqualTo("Blue Cafe\nMG Road");
    }

    @Test
    void locationWithoutAPlaceNameHasNoDescription() {
        assertThat(MessagePayloads.location(message("location").put("lat", 12.5).put("lng", 77.25)).description())
                .isNull();
    }

    @Test
    void locationAtZeroIsStillALocation() {
        assertThat(MessagePayloads.location(message("location").put("lat", 0.0).put("lng", 0.0))).isNotNull();
    }

    @Test
    void messageWithoutCoordinatesHasNoLocation() {
        assertThat(MessagePayloads.location(message("location"))).isNull();
    }

    @Test
    void coordinatesOnAnotherTypeAreNotALocation() {
        assertThat(MessagePayloads.location(message("chat").put("lat", 12.5).put("lng", 77.25))).isNull();
    }

    @Test
    void pollQuestionIsTheRawPollName() {
        assertThat(MessagePayloads.body(message("poll_creation").put("pollName", "Lunch where?")))
                .isEqualTo("Lunch where?");
        assertThat(MessagePayloads.body(message("poll_creation").put("body", "").put("pollName", "Lunch where?")))
                .isEqualTo("Lunch where?");
    }

    @Test
    void pollNameIsIgnoredOnOtherTypes() {
        assertThat(MessagePayloads.body(message("chat").put("body", "").put("pollName", "ignored"))).isEmpty();
    }

    @Test
    void pollOptionsAreReadFromRawObjects() {
        ObjectNode p = message("poll_creation");
        p.putArray("pollOptions").add(p.objectNode().put("name", "Pizza").put("localId", 0))
                .add(p.objectNode().put("name", "Sushi").put("localId", 1));
        assertThat(MessagePayloads.pollOptions(p)).containsExactly("Pizza", "Sushi");
    }

    @Test
    void pollOptionsMayBePlainStrings() {
        ObjectNode p = message("poll_creation");
        p.putArray("pollOptions").add("Pizza").add("Sushi");
        assertThat(MessagePayloads.pollOptions(p)).containsExactly("Pizza", "Sushi");
    }

    @Test
    void optionsOnAnotherTypeAreNotAPoll() {
        ObjectNode p = message("chat");
        p.putArray("pollOptions").add("Pizza");
        assertThat(MessagePayloads.pollOptions(p)).isNull();
    }

    @Test
    void messageWithoutOptionsHasNone() {
        assertThat(MessagePayloads.pollOptions(message("poll_creation"))).isNull();
        ObjectNode blank = message("poll_creation");
        blank.putArray("pollOptions").add(" ");
        assertThat(MessagePayloads.pollOptions(blank)).isNull();
    }

    @Test
    void directPathMeansTheMessageHasMedia() {
        ObjectNode p = message("image").put("directPath", "/v/t62/abc").put("mimetype", "image/jpeg");
        assertThat(MessagePayloads.hasMedia(p)).isTrue();
        assertThat(MessagePayloads.media(p).mimetype()).isEqualTo("image/jpeg");
    }

    @Test
    void noDirectPathAndNoFlagMeansNoMedia() {
        assertThat(MessagePayloads.hasMedia(message("chat"))).isFalse();
        assertThat(MessagePayloads.hasMedia(message("image").put("directPath", ""))).isFalse();
    }

    private static final Instant AD_AT = Instant.parse("2026-11-04T10:00:00Z");

    private static ObjectNode withContext(boolean fromMe) {
        ObjectNode p = message("chat").put("fromMe", fromMe);
        return p;
    }

    @Test
    void adClickReadsTheWaJsShape() {
        ObjectNode p = withContext(false);
        p.putObject("ctwaContext").put("sourceUrl", "https://fb.me/x").put("sourceId", "1200")
                .put("sourceApp", "facebook").put("title", "Diwali sale").put("description", "20% off")
                .put("conversionSource", "FB_Ads").put("thumbnail", "AAAA");
        AdClick ad = MessagePayloads.adClick(p, AD_AT);
        assertThat(ad.sourceUrl()).isEqualTo("https://fb.me/x");
        assertThat(ad.sourceId()).isEqualTo("1200");
        assertThat(ad.sourceApp()).isEqualTo("facebook");
        assertThat(ad.headline()).isEqualTo("Diwali sale");
        assertThat(ad.description()).isEqualTo("20% off");
        assertThat(ad.conversionSource()).isEqualTo("FB_Ads");
        assertThat(ad.at()).isEqualTo(AD_AT);
        assertThat(ad.raw()).containsEntry("sourceUrl", "https://fb.me/x").doesNotContainKey("thumbnail");
    }

    @Test
    void clickIdAliases() {
        ObjectNode p = withContext(false);
        p.putObject("ctwaContext").put("sourceId", "1").put("ctwa_clid", "CLK");
        assertThat(MessagePayloads.adClick(p, AD_AT).clickId()).isEqualTo("CLK");
        ((ObjectNode) p.get("ctwaContext")).put("ctwaClid", "PREFERRED");
        assertThat(MessagePayloads.adClick(p, AD_AT).clickId()).isEqualTo("PREFERRED");
    }

    @Test
    void headlineFallsBackToHeadline() {
        ObjectNode p = withContext(false);
        p.putObject("ctwaContext").put("sourceId", "1").put("headline", "Fallback");
        assertThat(MessagePayloads.adClick(p, AD_AT).headline()).isEqualTo("Fallback");
    }

    @Test
    void emptyContextIsNotAnAd() {
        ObjectNode p = withContext(false);
        p.putObject("ctwaContext");
        assertThat(MessagePayloads.adClick(p, AD_AT)).isNull();
        p.putObject("ctwaContext").put("sourceUrl", "  ").put("sourceId", "").put("title", "only a title");
        assertThat(MessagePayloads.adClick(p, AD_AT)).isNull();
        assertThat(MessagePayloads.adClick(withContext(false), AD_AT)).isNull();
    }

    @Test
    void outboundContextIsIgnored() {
        ObjectNode p = withContext(true);
        p.putObject("ctwaContext").put("sourceId", "1200");
        assertThat(MessagePayloads.adClick(p, AD_AT)).isNull();
    }

    @Test
    void longTextIsCut() {
        ObjectNode p = withContext(false);
        p.putObject("ctwaContext").put("sourceId", "1").put("title", "x".repeat(600));
        assertThat(MessagePayloads.adClick(p, AD_AT).headline()).hasSize(500);
    }

    @Test
    void oversizeRawIsDropped() {
        ObjectNode p = withContext(false);
        p.putObject("ctwaContext").put("sourceId", "1").put("description", "d".repeat(5 * 1024));
        AdClick ad = MessagePayloads.adClick(p, AD_AT);
        assertThat(ad.raw()).isNull();
        assertThat(ad.sourceId()).isEqualTo("1");
        assertThat(ad.description()).hasSize(500);
    }

    @Test
    void rawDropsKeysMongoRefuses() {
        ObjectNode p = withContext(false);
        p.putObject("ctwaContext").put("sourceId", "1").put("a.b", "x").put("$c", "y").put("ok", "z");
        assertThat(MessagePayloads.adClick(p, AD_AT).raw()).containsOnlyKeys("ok", "sourceId");
    }
}
