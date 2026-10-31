package me.nageshk.omnichannel.app.inbox.history;

import static java.util.concurrent.TimeUnit.SECONDS;
import static me.nageshk.omnichannel.app.inbox.history.HistoryImportRunnerIT.A;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import me.nageshk.omnichannel.db.inbox.HistoryChatStatus;
import me.nageshk.omnichannel.db.inbox.HistoryImportStatus;
import me.nageshk.omnichannel.db.inbox.HistoryWaiting;
import me.nageshk.omnichannel.db.inbox.WaWebHistoryImport;
import me.nageshk.omnichannel.db.inbox.WaWebHistoryImportChat;
import me.nageshk.omnichannel.db.inbox.WaWebMessage;
import me.nageshk.omnichannel.service.rpc.Envelope;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.node.ObjectNode;

/** Pages of two messages, so a chat takes several pages and a cursor. */
@TestPropertySource(properties = "omnichannel.history.page-size=2")
class HistoryImportRunnerPagingIT extends HistoryImportTestBase {

    @Test
    void pagesWithCursorWithoutLosingSameSecondMessages() throws Exception {
        try (HistoryDevice device = new HistoryDevice(port, testDevices, n1)) {
            ObjectNode last200 = HistoryDevice.msg(A, false, 200, 1, "s3");
            device.chats.put(A, List.of(HistoryDevice.msg(A, false, 100, 1, "first"),
                    HistoryDevice.msg(A, false, 200, 1, "s1"), HistoryDevice.msg(A, true, 200, 2, "s2"), last200,
                    HistoryDevice.msg(A, false, 300, 1, "last")));
            WaWebHistoryImport imp = importChats("919811100001");

            runner.tick().get(10, SECONDS);

            assertThat(reload(imp).getStatus()).isEqualTo(HistoryImportStatus.COMPLETED);
            assertThat(messages.findAll()).extracting(WaWebMessage::getBody)
                    .containsExactlyInAnyOrder("first", "s1", "s2", "s3", "last");
            List<Envelope> requests = device.requests("fetchChatHistory");
            assertThat(requests.size()).isGreaterThanOrEqualTo(3);
            // the first page is [s3 (200), last (300)]: oldest t 200, ids [s3]
            assertThat(requests.get(0).params().has("before")).isFalse();
            assertThat(requests.get(1).params().get("before").asLong()).isEqualTo(200);
            assertThat(HistoryImportRunnerIT.ids(requests.get(1)))
                    .containsExactly(last200.get("id").get("_serialized").asString());
        }
    }

    @Test
    void disconnectLeavesCursorAndWaits() throws Exception {
        List<ObjectNode> history = List.of(HistoryDevice.msg(A, false, 100, 1, "m1"),
                HistoryDevice.msg(A, false, 200, 1, "m2"), HistoryDevice.msg(A, false, 300, 1, "m3"),
                HistoryDevice.msg(A, false, 400, 1, "m4"), HistoryDevice.msg(A, false, 500, 1, "m5"));
        WaWebHistoryImport imp;
        HistoryDevice first = new HistoryDevice(port, testDevices, n1);
        Thread closing = new Thread(() -> {
            try {
                first.close();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        try {
            first.chats.put(A, history);
            AtomicInteger fetches = new AtomicInteger();
            first.override = req -> {
                if (!"fetchChatHistory".equals(req.method()) || fetches.incrementAndGet() == 1) return first.answer(req);
                closing.start();
                return null;
            };
            imp = importChats("919811100001");

            runner.tick().get(10, SECONDS);
        } finally {
            if (closing.getState() == Thread.State.NEW) first.close();
            else closing.join(5000);
        }

        WaWebHistoryImportChat item = itemsOf(imp).get(0);
        assertThat(item.getStatus()).isEqualTo(HistoryChatStatus.PENDING);
        assertThat(item.getCursor()).isNotNull();
        assertThat(item.getCursor().beforeT()).isEqualTo(400L);
        WaWebHistoryImport waiting = reload(imp);
        assertThat(waiting.getStatus()).isEqualTo(HistoryImportStatus.RUNNING);
        assertThat(waiting.getWaiting()).isEqualTo(HistoryWaiting.NO_DEVICE);
        assertThat(messages.count()).isEqualTo(2);

        try (HistoryDevice second = new HistoryDevice(port, testDevices, n1)) {
            second.chats.put(A, history);
            runner.tick().get(10, SECONDS);

            assertThat(second.requests("fetchChatHistory").get(0).params().get("before").asLong()).isEqualTo(400);
            WaWebHistoryImport done = reload(imp);
            assertThat(done.getStatus()).isEqualTo(HistoryImportStatus.COMPLETED);
            assertThat(done.getWaiting()).isNull();
            assertThat(messages.findAll()).extracting(WaWebMessage::getBody)
                    .containsExactlyInAnyOrder("m1", "m2", "m3", "m4", "m5");
            assertThat(done.getCounts().messagesInserted()).isEqualTo(5);
        }
    }
}
