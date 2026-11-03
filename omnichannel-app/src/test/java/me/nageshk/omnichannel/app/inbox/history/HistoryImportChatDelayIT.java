package me.nageshk.omnichannel.app.inbox.history;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import me.nageshk.omnichannel.db.inbox.HistoryImportStatus;
import me.nageshk.omnichannel.db.inbox.WaWebHistoryImport;
import me.nageshk.omnichannel.service.rpc.Envelope;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.node.ObjectNode;

/**
 * The pause after a chat is for WhatsApp: a chat that only read what the browser already held needs none; one that asked
 * for earlier messages, whatever came back, and one from a page script that does not say, are followed by it.
 */
@TestPropertySource(properties = "omnichannel.history.chat-delay=1s")
class HistoryImportChatDelayIT extends HistoryImportTestBase {

    static final String A = "919811100001@c.us";
    static final String B = "919811100002@c.us";
    static final String C = "919811100003@c.us";

    private long tickMillis() throws Exception {
        long start = System.nanoTime();
        runner.tick().get(20, SECONDS);
        return (System.nanoTime() - start) / 1_000_000;
    }

    @Test
    void chatsThatLoadedNothingAreNotFollowedByAPause() throws Exception {
        try (HistoryDevice device = new HistoryDevice(port, testDevices, n1)) {
            for (String chat : List.of(A, B, C)) device.chats.put(chat, List.of(HistoryDevice.msg(chat, false, 100, 1, "hi")));
            WaWebHistoryImport imp = importAll(null, null);

            long took = tickMillis();

            assertThat(reload(imp).getStatus()).isEqualTo(HistoryImportStatus.COMPLETED);
            assertThat(took).as("three chats, no pause").isLessThan(1500);
        }
    }

    @Test
    void aChatThatAskedForEarlierMessagesIsFollowedByThePauseThoughNoneCame() throws Exception {
        try (HistoryDevice device = new HistoryDevice(port, testDevices, n1)) {
            for (String chat : List.of(A, B)) device.chats.put(chat, List.of(HistoryDevice.msg(chat, false, 100, 1, "hi")));
            device.override = req -> {
                Envelope answer = device.answer(req);
                if ("fetchChatHistory".equals(req.method()) && answer.result() instanceof ObjectNode page) {
                    page.put("asked", true).put("loaded", false);
                }
                return answer;
            };
            WaWebHistoryImport imp = importAll(null, null);

            long took = tickMillis();

            assertThat(reload(imp).getStatus()).isEqualTo(HistoryImportStatus.COMPLETED);
            assertThat(took).as("a pause after each of the two chats").isGreaterThanOrEqualTo(1900);
        }
    }

    @Test
    void aPageScriptThatDoesNotSayWhetherItAskedIsFollowedByThePause() throws Exception {
        try (HistoryDevice device = new HistoryDevice(port, testDevices, n1)) {
            for (String chat : List.of(A, B)) device.chats.put(chat, List.of(HistoryDevice.msg(chat, false, 100, 1, "hi")));
            device.override = req -> {
                Envelope answer = device.answer(req);
                if ("fetchChatHistory".equals(req.method()) && answer.result() instanceof ObjectNode page) {
                    page.remove("asked");
                }
                return answer;
            };
            WaWebHistoryImport imp = importAll(null, null);

            long took = tickMillis();

            assertThat(reload(imp).getStatus()).isEqualTo(HistoryImportStatus.COMPLETED);
            assertThat(took).isGreaterThanOrEqualTo(1900);
        }
    }
}
