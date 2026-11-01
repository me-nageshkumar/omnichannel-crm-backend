package me.nageshk.omnichannel.app.inbox.history;

import static java.util.concurrent.TimeUnit.SECONDS;
import static me.nageshk.omnichannel.app.inbox.history.HistoryImportRunnerIT.A;
import static me.nageshk.omnichannel.app.inbox.history.HistoryImportRunnerIT.B;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import me.nageshk.omnichannel.db.inbox.HistoryChatStatus;
import me.nageshk.omnichannel.db.inbox.HistoryImportStatus;
import me.nageshk.omnichannel.db.inbox.WaWebHistoryImport;
import me.nageshk.omnichannel.db.inbox.WaWebHistoryImportChat;
import me.nageshk.omnichannel.db.inbox.WaWebMessage;
import me.nageshk.omnichannel.service.rpc.Envelope;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Pause, play and stop, with pages of two messages. */
@TestPropertySource(properties = {"omnichannel.history.page-size=2", "omnichannel.history.page-delay=0ms",
        "omnichannel.history.chat-delay=0ms", "omnichannel.history.max-empty-pages=3"})
class HistoryImportPauseIT extends HistoryImportTestBase {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static void seedFive(HistoryDevice device) {
        device.chats.put(A, List.of(HistoryDevice.msg(A, false, 100, 1, "m1"),
                HistoryDevice.msg(A, false, 200, 1, "m2"), HistoryDevice.msg(A, false, 300, 1, "m3"),
                HistoryDevice.msg(A, false, 400, 1, "m4"), HistoryDevice.msg(A, false, 500, 1, "m5")));
    }

    /** Runs {@code action} once, while the first page is on its way to the backend. */
    private static void onFirstPage(HistoryDevice device, Runnable action) {
        AtomicBoolean done = new AtomicBoolean();
        device.override = req -> {
            if ("fetchChatHistory".equals(req.method()) && done.compareAndSet(false, true)) action.run();
            return device.answer(req);
        };
    }

    private List<String> stored() {
        return messages.findAll().stream().map(WaWebMessage::getBody).toList();
    }

    @Test
    void pauseKeepsThePageThatWasOnItsWayAndFetchesNoMore() throws Exception {
        try (HistoryDevice device = new HistoryDevice(port, testDevices, n1)) {
            seedFive(device);
            WaWebHistoryImport imp = importChats("919811100001");
            onFirstPage(device, () -> service.pause(brand.getId(), imp.getId()));

            runner.tick().get(10, SECONDS);

            WaWebHistoryImport paused = reload(imp);
            assertThat(paused.getStatus()).isEqualTo(HistoryImportStatus.PAUSED);
            assertThat(paused.isActive()).isTrue();
            assertThat(device.requests("fetchChatHistory")).hasSize(1);
            assertThat(stored()).containsExactlyInAnyOrder("m4", "m5");
            assertThat(paused.getCounts().messagesSeen()).isEqualTo(2);
            assertThat(paused.getCounts().messagesInserted()).isEqualTo(2);
            WaWebHistoryImportChat item = itemsOf(imp).get(0);
            assertThat(item.getStatus()).isEqualTo(HistoryChatStatus.PENDING);
            assertThat(item.getCursor().beforeT()).isEqualTo(400);
        }
    }

    @Test
    void playContinuesFromTheCursorWithoutRepeatingAMessage() throws Exception {
        try (HistoryDevice device = new HistoryDevice(port, testDevices, n1)) {
            seedFive(device);
            WaWebHistoryImport imp = importChats("919811100001");
            onFirstPage(device, () -> service.pause(brand.getId(), imp.getId()));
            runner.tick().get(10, SECONDS);

            WaWebHistoryImport resumed = service.resume(brand.getId(), imp.getId());
            assertThat(resumed.getStatus()).isEqualTo(HistoryImportStatus.RUNNING);
            runner.tick().get(10, SECONDS);

            WaWebHistoryImport done = reload(imp);
            assertThat(done.getStatus()).isEqualTo(HistoryImportStatus.COMPLETED);
            assertThat(stored()).containsExactlyInAnyOrder("m1", "m2", "m3", "m4", "m5");
            assertThat(done.getCounts().messagesSeen()).isEqualTo(5);
            assertThat(done.getCounts().messagesInserted()).isEqualTo(5);
        }
    }

    @Test
    void stopKeepsAndCountsThePageThatWasOnItsWay() throws Exception {
        try (HistoryDevice device = new HistoryDevice(port, testDevices, n1)) {
            seedFive(device);
            WaWebHistoryImport imp = importChats("919811100001");
            onFirstPage(device, () -> service.cancel(brand.getId(), imp.getId()));

            runner.tick().get(10, SECONDS);

            WaWebHistoryImport stopped = reload(imp);
            assertThat(stopped.getStatus()).isEqualTo(HistoryImportStatus.CANCELLED);
            assertThat(device.requests("fetchChatHistory")).hasSize(1);
            assertThat(stored()).containsExactlyInAnyOrder("m4", "m5");
            assertThat(stopped.getCounts().messagesSeen()).isEqualTo(2);
            assertThat(stopped.getCounts().messagesInserted()).isEqualTo(2);
        }
    }

    @Test
    void aPausedImportIsNotRun() throws Exception {
        try (HistoryDevice device = new HistoryDevice(port, testDevices, n1)) {
            seedFive(device);
            WaWebHistoryImport imp = importChats("919811100001");
            service.pause(brand.getId(), imp.getId());

            runner.tick().get(10, SECONDS);

            assertThat(reload(imp).getStatus()).isEqualTo(HistoryImportStatus.PAUSED);
            assertThat(device.requests()).isEmpty();
            assertThat(stored()).isEmpty();
        }
    }

    @Test
    void aPausedImportDoesNotHoldUpTheNumber() throws Exception {
        try (HistoryDevice device = new HistoryDevice(port, testDevices, n1)) {
            seedFive(device);
            device.chats.put(B, List.of(HistoryDevice.msg(B, false, 150, 1, "b1")));
            WaWebHistoryImport paused = importChats("919811100001");
            service.pause(brand.getId(), paused.getId());
            WaWebHistoryImport next = importChats("919811100002");

            runner.tick().get(10, SECONDS);

            assertThat(reload(next).getStatus()).isEqualTo(HistoryImportStatus.COMPLETED);
            assertThat(reload(paused).getStatus()).isEqualTo(HistoryImportStatus.PAUSED);
            assertThat(stored()).containsExactly("b1");
        }
    }

    @Test
    void pausingOnTheLastPageCompletes() throws Exception {
        try (HistoryDevice device = new HistoryDevice(port, testDevices, n1)) {
            device.chats.put(A, List.of(HistoryDevice.msg(A, false, 100, 1, "m1")));
            WaWebHistoryImport imp = importChats("919811100001");
            onFirstPage(device, () -> service.pause(brand.getId(), imp.getId()));

            runner.tick().get(10, SECONDS);

            assertThat(reload(imp).getStatus()).isEqualTo(HistoryImportStatus.COMPLETED);
            assertThat(stored()).containsExactly("m1");
        }
    }

    @Test
    void countsMoveWithEveryPage() throws Exception {
        try (HistoryDevice device = new HistoryDevice(port, testDevices, n1)) {
            seedFive(device);
            WaWebHistoryImport imp = importChats("919811100001");
            long[] seenAtSecondPage = {-1};
            device.override = req -> {
                if ("fetchChatHistory".equals(req.method()) && device.requests("fetchChatHistory").size() == 2) {
                    seenAtSecondPage[0] = reload(imp).getCounts().messagesSeen();
                }
                return device.answer(req);
            };

            runner.tick().get(10, SECONDS);

            assertThat(seenAtSecondPage[0]).isEqualTo(2);
        }
    }

    /** The page of the bug: the same message again and again, its id lost, always "there is more". */
    private static ObjectNode repeating(ObjectNode message) {
        ObjectNode result = MAPPER.createObjectNode();
        result.putArray("messages").add(message);
        result.putObject("oldest").put("t", message.get("t").asLong()).putArray("ids").addNull();
        result.put("hasMore", true);
        return result;
    }

    @Test
    void aPageThatRepeatsItsCursorEndsTheChatInsteadOfLooping() throws Exception {
        try (HistoryDevice device = new HistoryDevice(port, testDevices, n1)) {
            ObjectNode message = HistoryDevice.msg(A, false, 100, 1, "m1");
            WaWebHistoryImport imp = importChats("919811100001");
            device.override = req -> Envelope.response(req.id(), repeating(message));

            runner.tick().get(10, SECONDS);

            WaWebHistoryImportChat item = itemsOf(imp).get(0);
            assertThat(item.getStatus()).isEqualTo(HistoryChatStatus.DONE);
            assertThat(item.isOlderUnavailable()).isTrue();
            assertThat(item.getCounts().seen()).isEqualTo(1);
            assertThat(device.requests("fetchChatHistory")).hasSize(4);
            assertThat(reload(imp).getStatus()).isEqualTo(HistoryImportStatus.COMPLETED);
        }
    }

    @Test
    void pagesThatKeepNothingButLoadedMoreKeepTheFetchGoing() throws Exception {
        try (HistoryDevice device = new HistoryDevice(port, testDevices, n1)) {
            seedFive(device);
            WaWebHistoryImport imp = importChats("919811100001");
            AtomicInteger walking = new AtomicInteger(5);
            device.override = req -> {
                if (!"fetchChatHistory".equals(req.method()) || walking.getAndDecrement() <= 0) {
                    return device.answer(req);
                }
                ObjectNode result = MAPPER.createObjectNode();
                result.putArray("messages");
                result.putNull("oldest");
                result.put("hasMore", true).put("loaded", true);
                return Envelope.response(req.id(), result);
            };

            runner.tick().get(10, SECONDS);

            WaWebHistoryImportChat item = itemsOf(imp).get(0);
            assertThat(item.isOlderUnavailable()).isFalse();
            assertThat(item.getCounts().seen()).isEqualTo(5);
            assertThat(stored()).hasSize(5);
        }
    }

    @Test
    void whatsAppWithholdingOlderMessagesEndsTheChatDone() throws Exception {
        try (HistoryDevice device = new HistoryDevice(port, testDevices, n1)) {
            WaWebHistoryImport imp = importChats("919811100001");
            device.override = req -> {
                ObjectNode result = MAPPER.createObjectNode();
                result.putArray("messages");
                result.putNull("oldest");
                result.put("hasMore", true);
                return Envelope.response(req.id(), result);
            };

            runner.tick().get(10, SECONDS);

            WaWebHistoryImportChat item = itemsOf(imp).get(0);
            assertThat(item.getStatus()).isEqualTo(HistoryChatStatus.DONE);
            assertThat(item.isOlderUnavailable()).isTrue();
            assertThat(item.getError()).isNull();
            assertThat(device.requests("fetchChatHistory")).hasSize(3);
        }
    }
}
