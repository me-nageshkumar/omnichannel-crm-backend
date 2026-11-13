package me.nageshk.omnichannel.service.chatbot.node;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Map;
import me.nageshk.omnichannel.db.inbox.WaWebMessage;
import me.nageshk.omnichannel.service.chatbot.flow.settings.AnswerType;
import org.junit.jupiter.api.Test;

/** Spec §4.2: what counts as a valid answer per answer type. */
class AnswersTest {

    private static WaWebMessage text(String body) {
        WaWebMessage m = new WaWebMessage();
        m.setType("text");
        m.setBody(body);
        return m;
    }

    private static WaWebMessage media(String type, String mime, String filename) {
        WaWebMessage m = new WaWebMessage();
        m.setId("m1");
        m.setType(type);
        m.setMedia(new WaWebMessage.Media(mime, filename, 1234L, null, null));
        return m;
    }

    @Test
    void textNeedsSomething() {
        assertThat(Answers.parse(AnswerType.TEXT, text("  hi "))).contains("hi");
        assertThat(Answers.parse(AnswerType.TEXT, text("   "))).isEmpty();
        assertThat(Answers.parse(AnswerType.TEXT, media("image", "image/png", "a.png"))).isEmpty();
    }

    @Test
    void numberStripsCommas() {
        assertThat(Answers.parse(AnswerType.NUMBER, text("5,000"))).contains(new BigDecimal("5000"));
        assertThat(Answers.parse(AnswerType.NUMBER, text("-12.5"))).contains(new BigDecimal("-12.5"));
        assertThat(Answers.parse(AnswerType.NUMBER, text("five"))).isEmpty();
    }

    @Test
    void emailShape() {
        assertThat(Answers.parse(AnswerType.EMAIL, text("Veenu@example.com"))).contains("Veenu@example.com");
        assertThat(Answers.parse(AnswerType.EMAIL, text("veenu@localhost"))).isEmpty();
    }

    @Test
    void phoneNeedsNineToFifteenDigits() {
        assertThat(Answers.parse(AnswerType.PHONE, text("+91 97997-50743"))).contains("919799750743");
        assertThat(Answers.parse(AnswerType.PHONE, text("12345"))).isEmpty();
        assertThat(Answers.parse(AnswerType.PHONE, text("1234567890123456"))).isEmpty();
    }

    @Test
    void dateAcceptsTwoFormats() {
        assertThat(Answers.parse(AnswerType.DATE, text("13/11/2026"))).contains("2026-11-13");
        assertThat(Answers.parse(AnswerType.DATE, text("2026-11-13"))).contains("2026-11-13");
        assertThat(Answers.parse(AnswerType.DATE, text("13-11-2026"))).isEmpty();
        assertThat(Answers.parse(AnswerType.DATE, text("31/02/2026"))).isEmpty();
    }

    @Test
    void locationFromALocationMessage() {
        WaWebMessage m = new WaWebMessage();
        m.setType("location");
        m.setLocation(new WaWebMessage.Location(12.9716, 77.5946, "Store"));
        assertThat(Answers.parse(AnswerType.LOCATION, m)).contains(Map.of("latitude", 12.9716, "longitude", 77.5946));
        assertThat(Answers.parse(AnswerType.LOCATION, text("12.9, 77.5"))).isEmpty();
    }

    @Test
    void fileTypes() {
        WaWebMessage img = media("image", "image/jpeg", "a.jpg");
        assertThat(Answers.parse(AnswerType.FILE, img)).contains(Map.of("messageId", "m1", "mimeType", "image/jpeg", "filename", "a.jpg", "size", 1234L));
        assertThat(Answers.parse(AnswerType.IMAGE, img)).isPresent();
        assertThat(Answers.parse(AnswerType.IMAGE, media("document", "application/pdf", "a.pdf"))).isEmpty();
        assertThat(Answers.parse(AnswerType.DOCUMENT, media("document", "application/pdf", "a.pdf"))).isPresent();
        assertThat(Answers.parse(AnswerType.AUDIO, media("audio", "audio/ogg", null))).isPresent();
        assertThat(Answers.parse(AnswerType.VIDEO, media("video", "video/mp4", "v.mp4"))).isPresent();
        assertThat(Answers.parse(AnswerType.FILE, text("hi"))).isEmpty();
    }
}
