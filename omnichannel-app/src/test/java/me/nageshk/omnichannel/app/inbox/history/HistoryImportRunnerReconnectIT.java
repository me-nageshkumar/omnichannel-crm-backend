package me.nageshk.omnichannel.app.inbox.history;

import static java.util.concurrent.TimeUnit.SECONDS;
import static me.nageshk.omnichannel.app.inbox.history.HistoryImportRunnerIT.A;
import static me.nageshk.omnichannel.app.inbox.history.HistoryImportRunnerIT.B;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import me.nageshk.omnichannel.db.inbox.HistoryChatStatus;
import me.nageshk.omnichannel.db.inbox.HistoryImportStatus;
import me.nageshk.omnichannel.db.inbox.WaWebHistoryImport;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * Three seconds after a chat that loaded from the phone, long enough for the extension to reconnect while an import is
 * running.
 */
@TestPropertySource(properties = "omnichannel.history.chat-delay=3s")
class HistoryImportRunnerReconnectIT extends HistoryImportTestBase {

    @Test
    void deviceIsResolvedAgainForEachChat() throws Exception {
        HistoryDevice first = new HistoryDevice(port, testDevices, n1);
        AtomicBoolean answered = new AtomicBoolean();
        Thread closing = new Thread(() -> {
            try {
                first.close();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        CompletableFuture<Void> tick;
        WaWebHistoryImport imp;
        try {
            HistoryImportRunnerIT.seedTwoChats(first);
            // answers chat A's only page, then goes away
            first.override = req -> {
                if (!"fetchChatHistory".equals(req.method()) || !answered.compareAndSet(false, true)) {
                    return first.answer(req);
                }
                var reply = first.answer(req);
                // it loaded from the phone, so the three seconds follow it: time for the extension to come back
                ((tools.jackson.databind.node.ObjectNode) reply.result()).put("loaded", true);
                closing.start();
                return reply;
            };
            imp = importChats("919811100001", "919811100002");
            tick = runner.tick();
            HistoryImportRunnerIT.awaitRequest(first, "fetchChatHistory");
        } finally {
            Thread.sleep(50);
            if (closing.getState() == Thread.State.NEW) first.close();
            else closing.join(5000);
        }

        try (HistoryDevice second = new HistoryDevice(port, testDevices, n1)) {
            HistoryImportRunnerIT.seedTwoChats(second);
            tick.get(20, SECONDS);

            assertThat(second.requests("fetchChatHistory")).hasSize(1);
            assertThat(second.requests("fetchChatHistory").get(0).params().get("chatId").asString()).isEqualTo(B);
            assertThat(itemsOf(imp)).allSatisfy(i -> assertThat(i.getStatus()).isEqualTo(HistoryChatStatus.DONE));
            assertThat(itemsOf(imp)).extracting(i -> i.getRemoteId()).containsExactly(A, B);
            WaWebHistoryImport done = reload(imp);
            assertThat(done.getStatus()).isEqualTo(HistoryImportStatus.COMPLETED);
            assertThat(done.getWaiting()).isNull();
            assertThat(messages.count()).isEqualTo(5);
        }
    }
}
