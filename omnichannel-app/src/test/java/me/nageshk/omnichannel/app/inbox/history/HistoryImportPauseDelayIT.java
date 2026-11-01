package me.nageshk.omnichannel.app.inbox.history;

import static java.util.concurrent.TimeUnit.SECONDS;
import static me.nageshk.omnichannel.app.inbox.history.HistoryImportRunnerIT.A;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import me.nageshk.omnichannel.db.inbox.HistoryImportStatus;
import me.nageshk.omnichannel.db.inbox.WaWebHistoryImport;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/** A click during the wait between pages, which is where most clicks land. */
@TestPropertySource(properties = {"omnichannel.history.page-size=2", "omnichannel.history.page-delay=700ms",
        "omnichannel.history.chat-delay=0ms"})
class HistoryImportPauseDelayIT extends HistoryImportTestBase {

    private static void seedFive(HistoryDevice device) {
        device.chats.put(A, List.of(HistoryDevice.msg(A, false, 100, 1, "m1"),
                HistoryDevice.msg(A, false, 200, 1, "m2"), HistoryDevice.msg(A, false, 300, 1, "m3"),
                HistoryDevice.msg(A, false, 400, 1, "m4"), HistoryDevice.msg(A, false, 500, 1, "m5")));
    }

    /** Waits for the first page to be stored and counted: the runner is now sleeping before the next one. */
    private void awaitFirstPage(WaWebHistoryImport imp) throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            if (reload(imp).getCounts().messagesSeen() == 2) return;
            Thread.sleep(5);
        }
        throw new AssertionError("the first page never arrived");
    }

    @Test
    void aPauseDuringTheWaitAsksForNoMorePages() throws Exception {
        try (HistoryDevice device = new HistoryDevice(port, testDevices, n1)) {
            seedFive(device);
            WaWebHistoryImport imp = importChats("919811100001");

            CompletableFuture<Void> tick = runner.tick();
            awaitFirstPage(imp);
            service.pause(brand.getId(), imp.getId());
            tick.get(10, SECONDS);

            assertThat(device.requests("fetchChatHistory")).hasSize(1);
            assertThat(messages.count()).isEqualTo(2);
            assertThat(reload(imp).getStatus()).isEqualTo(HistoryImportStatus.PAUSED);
        }
    }

    @Test
    void aStopDuringTheWaitAsksForNoMorePages() throws Exception {
        try (HistoryDevice device = new HistoryDevice(port, testDevices, n1)) {
            seedFive(device);
            WaWebHistoryImport imp = importChats("919811100001");

            CompletableFuture<Void> tick = runner.tick();
            awaitFirstPage(imp);
            service.cancel(brand.getId(), imp.getId());
            tick.get(10, SECONDS);

            assertThat(device.requests("fetchChatHistory")).hasSize(1);
            assertThat(messages.count()).isEqualTo(2);
        }
    }
}
