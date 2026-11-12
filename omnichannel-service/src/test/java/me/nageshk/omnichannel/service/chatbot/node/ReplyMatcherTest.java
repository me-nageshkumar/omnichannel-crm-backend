package me.nageshk.omnichannel.service.chatbot.node;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import me.nageshk.omnichannel.common.channel.whatsapp.message.Interactive;
import me.nageshk.omnichannel.db.inbox.WaWebMessage;
import org.junit.jupiter.api.Test;

/** Spec §4.2: a reply picks a button or row by the interactive reply id when the event carries one, else by title. */
class ReplyMatcherTest {

    private static Interactive buttons() {
        return new Interactive("button", null, new Interactive.Body("Book?"), null, new Interactive.Action(List.of(
                new Interactive.ActionButton("reply", new Interactive.Reply("yes", "Yes, book a demo!")),
                new Interactive.ActionButton("reply", new Interactive.Reply("no", "No thanks"))), null, null, null, null));
    }

    private static WaWebMessage text(String body) {
        WaWebMessage m = new WaWebMessage();
        m.setType("text");
        m.setBody(body);
        return m;
    }

    @Test
    void matchesButtonReplyIdFromEvent() {
        WaWebMessage m = text("whatever the title says");
        m.setType("button");
        Map<String, Object> event = Map.of("button", Map.of("payload", "no", "text", "No thanks"));
        assertThat(ReplyMatcher.match(buttons(), m, event)).map(Interactive.Reply::id).contains("no");
        Map<String, Object> list = Map.of("interactive", Map.of("type", "list_reply", "list_reply", Map.of("id", "yes", "title", "x")));
        assertThat(ReplyMatcher.match(buttons(), m, list)).map(Interactive.Reply::id).contains("yes");
    }

    @Test
    void matchesTitleIgnoringCaseAndPunctuation() {
        // the project's bare(): case and punctuation at the edges are ignored, inner punctuation is not
        assertThat(ReplyMatcher.match(buttons(), text("YES, BOOK A DEMO"), null)).map(Interactive.Reply::id).contains("yes");
        assertThat(ReplyMatcher.match(buttons(), text("  No thanks. "), null)).map(Interactive.Reply::id).contains("no");
    }

    @Test
    void noMatchIsEmpty() {
        assertThat(ReplyMatcher.match(buttons(), text("maybe"), null)).isEmpty();
        assertThat(ReplyMatcher.match(buttons(), text(null), null)).isEmpty();
        assertThat(ReplyMatcher.match(null, text("yes"), null)).isEmpty();
    }
}
