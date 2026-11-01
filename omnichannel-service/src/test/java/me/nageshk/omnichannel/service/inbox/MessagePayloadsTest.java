package me.nageshk.omnichannel.service.inbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
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
}
