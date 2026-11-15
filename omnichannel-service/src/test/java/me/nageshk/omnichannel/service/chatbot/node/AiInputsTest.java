package me.nageshk.omnichannel.service.chatbot.node;

import static org.assertj.core.api.Assertions.assertThat;

import me.nageshk.omnichannel.db.inbox.WaWebMessage;
import org.junit.jupiter.api.Test;

/** Spec §5.5: what the model reads for each kind of customer message. */
class AiInputsTest {

    private static WaWebMessage message(String type, String body) {
        WaWebMessage m = new WaWebMessage();
        m.setType(type);
        m.setBody(body);
        return m;
    }

    private static WaWebMessage media(String type, String caption, String filename) {
        WaWebMessage m = message(type, caption);
        m.setMedia(new WaWebMessage.Media("application/octet-stream", filename, 10L, null, null));
        return m;
    }

    @Test
    void textIsItsBody() {
        assertThat(AiInputs.text(message("text", "hello there"))).isEqualTo("hello there");
    }

    @Test
    void buttonAndListRepliesAreTheirTitles() {
        assertThat(AiInputs.text(message("button", "Book a demo"))).isEqualTo("Book a demo");
        assertThat(AiInputs.text(message("interactive", "Large"))).isEqualTo("Large");
    }

    @Test
    void locationIsCoordinatesAndName() {
        WaWebMessage m = message("location", null);
        m.setLocation(new WaWebMessage.Location(12.9716, 77.5946, "Store"));
        assertThat(AiInputs.text(m)).isEqualTo("[location: 12.9716, 77.5946 Store]");
        m.setLocation(new WaWebMessage.Location(12.9716, 77.5946, null));
        assertThat(AiInputs.text(m)).isEqualTo("[location: 12.9716, 77.5946]");
    }

    @Test
    void mediaIsAStandIn() {
        assertThat(AiInputs.text(media("image", "red shirt", null))).isEqualTo("[customer sent an image: red shirt]");
        assertThat(AiInputs.text(media("image", null, null))).isEqualTo("[customer sent an image]");
        assertThat(AiInputs.text(media("video", "unboxing", null))).isEqualTo("[customer sent a video: unboxing]");
        assertThat(AiInputs.text(media("document", null, "invoice.pdf")))
                .isEqualTo("[customer sent a document: invoice.pdf]");
        assertThat(AiInputs.text(media("audio", null, null))).isEqualTo("[customer sent an audio message]");
        assertThat(AiInputs.text(media("ptt", null, null))).isEqualTo("[customer sent a voice note]");
        assertThat(AiInputs.text(media("sticker", "", null))).isEqualTo("[customer sent a sticker]");
    }

    @Test
    void cardsAndPolls() {
        assertThat(AiInputs.text(message("contacts", null))).isEqualTo("[customer sent a contact card]");
        assertThat(AiInputs.text(message("poll_creation", "Lunch?"))).isEqualTo("[customer sent a poll: Lunch?]");
    }
}
