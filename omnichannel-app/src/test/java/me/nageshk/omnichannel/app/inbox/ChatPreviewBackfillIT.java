package me.nageshk.omnichannel.app.inbox;

import static org.assertj.core.api.Assertions.assertThat;

import me.nageshk.omnichannel.db.inbox.MessageDirection;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import me.nageshk.omnichannel.db.inbox.WaWebMessage;
import me.nageshk.omnichannel.service.inbox.ChatPreviewBackfill;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ChatPreviewBackfillIT extends InboxApiTestBase {

    private static final String THUMBNAIL = "/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRof".repeat(10);
    private static final long AT_LAST_MESSAGE = 60;

    @Autowired ChatPreviewBackfill backfill;

    /** A chat whose stored preview is {@code preview} and whose newest message is {@code body} of {@code type}. */
    private WaWebChat chatWith(String type, String body, String preview, long messageSeconds) {
        WaWebChat c = chat("Alice", "911", 1);
        WaWebMessage m = message(c, messageSeconds, body);
        m.setType(type);
        messages.save(m);
        c.setLastMessage(new WaWebChat.LastMessage(MessageDirection.IN, type, preview, null));
        return chats.save(c);
    }

    private WaWebChat reload(WaWebChat c) {
        return chats.findById(c.getId()).orElseThrow();
    }

    @Test
    void thumbnailPreviewBecomesTheLabel() {
        WaWebChat c = chatWith("image", THUMBNAIL, THUMBNAIL.substring(0, 120), AT_LAST_MESSAGE);
        backfill.run(null);
        WaWebChat after = reload(c);
        assertThat(after.getLastMessage().text()).isEqualTo("📷 Photo");
        assertThat(after.getLastMessage().direction()).isEqualTo(MessageDirection.IN);
        assertThat(after.getUnreadCount()).isEqualTo(2);
    }

    @Test
    void aCallShownAsUnsupportedGetsItsLabel() {
        WaWebChat c = chatWith("call_log", null, "Unsupported message", AT_LAST_MESSAGE);
        backfill.run(null);
        assertThat(reload(c).getLastMessage().text()).isEqualTo("📞 Call");
    }

    @Test
    void aBusinessMessageShownAsUnsupportedGetsItsLabel() {
        WaWebChat c = chatWith("biz_content_placeholder", null, "Unsupported message", AT_LAST_MESSAGE);
        backfill.run(null);
        assertThat(reload(c).getLastMessage().text()).isEqualTo("💼 Business message");
    }

    @Test
    void aContactCardShownByTypeGetsTheNameOnIt() {
        WaWebChat c = chatWith("vcard", "BEGIN:VCARD\nFN:Wife\nEND:VCARD", "👤 Contact card", AT_LAST_MESSAGE);
        backfill.run(null);
        assertThat(reload(c).getLastMessage().text()).isEqualTo("👤 Wife");
    }

    @Test
    void aNoticeShownAsUnsupportedGetsItsLabel() {
        WaWebChat c = chatWith("notification_template", "", "Unsupported message", AT_LAST_MESSAGE);
        backfill.run(null);
        assertThat(reload(c).getLastMessage().text()).isEqualTo("WhatsApp notice");
    }

    @Test
    void aNoticeGivesWayToTheRealMessageBeforeIt() {
        WaWebChat c = chatWith("notification_template", "", "Unsupported message", AT_LAST_MESSAGE);
        WaWebMessage photo = message(c, AT_LAST_MESSAGE - 1, THUMBNAIL);
        photo.setType("image");
        messages.save(photo);
        java.time.Instant at = reload(c).getLastMessageAt();

        backfill.run(null);

        WaWebChat after = reload(c);
        assertThat(after.getLastMessage().text()).isEqualTo("📷 Photo");
        assertThat(after.getLastMessage().type()).isEqualTo("image");
        assertThat(after.getLastMessageAt()).isEqualTo(at);
    }

    @Test
    void anotherUnsupportedMessageIsLeftAlone() {
        WaWebChat c = chatWith("whatever", null, "Unsupported message", AT_LAST_MESSAGE);
        backfill.run(null);
        assertThat(reload(c).getLastMessage().text()).isEqualTo("Unsupported message");
    }

    @Test
    void cutPreviewGetsTheWholeMessage() {
        String whole = "x".repeat(300);
        WaWebChat c = chatWith("chat", whole, whole.substring(0, 120), AT_LAST_MESSAGE);
        backfill.run(null);
        assertThat(reload(c).getLastMessage().text()).isEqualTo(whole);
    }

    @Test
    void storedMessageIsNotTouched() {
        WaWebChat c = chatWith("image", THUMBNAIL, THUMBNAIL.substring(0, 120), AT_LAST_MESSAGE);
        backfill.run(null);
        assertThat(messages.findAll().stream().filter(m -> c.getId().equals(m.getChatId())).toList())
                .singleElement().extracting(WaWebMessage::getBody).isEqualTo(THUMBNAIL);
    }

    @Test
    void shortPreviewIsLeftAlone() {
        WaWebChat c = chatWith("chat", "something else", "hello", AT_LAST_MESSAGE);
        backfill.run(null);
        assertThat(reload(c).getLastMessage().text()).isEqualTo("hello");
    }

    @Test
    void previewOfAnotherMessageIsLeftAlone() {
        String cut = "y".repeat(120);
        WaWebChat c = chatWith("chat", "x".repeat(300), cut, AT_LAST_MESSAGE + 1);
        backfill.run(null);
        assertThat(reload(c).getLastMessage().text()).isEqualTo(cut);
    }

    @Test
    void previewOfAnotherTypeIsLeftAlone() {
        String cut = "y".repeat(120);
        WaWebChat c = chatWith("chat", "x".repeat(300), cut, AT_LAST_MESSAGE);
        c.setLastMessage(new WaWebChat.LastMessage(MessageDirection.IN, "image", cut, null));
        chats.save(c);
        backfill.run(null);
        assertThat(reload(c).getLastMessage().text()).isEqualTo(cut);
    }

    @Test
    void wholePreviewLongerThanTheOldCutIsNotRevisited() {
        String preview = "y".repeat(130);
        WaWebChat c = chatWith("chat", "x".repeat(300), preview, AT_LAST_MESSAGE);
        backfill.run(null);
        assertThat(reload(c).getLastMessage().text()).isEqualTo(preview);
    }

    @Test
    void secondRunChangesNothing() {
        WaWebChat c = chatWith("chat", "x".repeat(300), "x".repeat(120), AT_LAST_MESSAGE);
        backfill.run(null);
        Long version = reload(c).getVersion();
        backfill.run(null);
        assertThat(reload(c).getVersion()).isEqualTo(version);
    }
}
