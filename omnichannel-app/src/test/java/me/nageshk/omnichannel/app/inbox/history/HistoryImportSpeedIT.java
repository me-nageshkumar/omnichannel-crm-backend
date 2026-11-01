package me.nageshk.omnichannel.app.inbox.history;

import static java.util.concurrent.TimeUnit.SECONDS;
import static me.nageshk.omnichannel.app.inbox.history.HistoryImportRunnerIT.A;
import static me.nageshk.omnichannel.app.inbox.history.HistoryImportRunnerIT.B;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import me.nageshk.omnichannel.db.inbox.HistoryChatStatus;
import me.nageshk.omnichannel.db.inbox.HistoryImportStatus;
import me.nageshk.omnichannel.db.inbox.WaWebHistoryImport;
import me.nageshk.omnichannel.db.inbox.WaWebHistoryImportChat;
import me.nageshk.omnichannel.service.rpc.Envelope;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Chats that cannot hold anything in range, and chats that never stop claiming to load. */
@TestPropertySource(properties = {"omnichannel.history.max-idle-pages=4", "omnichannel.history.max-empty-pages=3"})
class HistoryImportSpeedIT extends HistoryImportTestBase {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long FROM = 10_000_000L;
    private static final long DAY = Duration.ofDays(1).toSeconds();

    private WaWebHistoryImport importSince(long from) {
        return importAll(Instant.ofEpochSecond(from), null);
    }

    private List<String> fetched(HistoryDevice device) {
        return device.requests("fetchChatHistory").stream().map(e -> e.params().path("chatId").asString()).distinct()
                .toList();
    }

    @Test
    void aChatLastActiveBeforeTheRangeIsNotFetched() throws Exception {
        try (HistoryDevice device = new HistoryDevice(port, testDevices, n1)) {
            device.chats.put(A, List.of(HistoryDevice.msg(A, false, FROM - 2 * DAY, 1, "old")));
            device.chats.put(B, List.of(HistoryDevice.msg(B, false, FROM + 100, 1, "new")));
            WaWebHistoryImport imp = importSince(FROM);

            runner.tick().get(10, SECONDS);

            assertThat(fetched(device)).containsExactly(B);
            WaWebHistoryImport done = reload(imp);
            assertThat(done.getStatus()).isEqualTo(HistoryImportStatus.COMPLETED);
            assertThat(done.getCounts().chatsTotal()).isEqualTo(1);
            assertThat(messages.findAll()).hasSize(1);
        }
    }

    @Test
    void aChatJustBeforeTheRangeIsStillFetchedInCaseItsTimeLags() throws Exception {
        try (HistoryDevice device = new HistoryDevice(port, testDevices, n1)) {
            device.chats.put(A, List.of(HistoryDevice.msg(A, false, FROM - DAY / 2, 1, "close")));
            importSince(FROM);

            runner.tick().get(10, SECONDS);

            assertThat(fetched(device)).containsExactly(A);
        }
    }

    @Test
    void aChatWithoutALastActivityTimeIsStillFetched() throws Exception {
        try (HistoryDevice device = new HistoryDevice(port, testDevices, n1)) {
            device.chats.put(A, List.of());
            importSince(FROM);

            runner.tick().get(10, SECONDS);

            assertThat(fetched(device)).containsExactly(A);
        }
    }

    @Test
    void aLastActivityTimeOfZeroMeansNoneAndTheChatIsStillFetched() throws Exception {
        try (HistoryDevice device = new HistoryDevice(port, testDevices, n1)) {
            device.chats.put(A, List.of(HistoryDevice.msg(A, false, 0, 1, "no time")));
            importSince(FROM);

            runner.tick().get(10, SECONDS);

            assertThat(fetched(device)).containsExactly(A);
        }
    }

    @Test
    void anAllTimeFetchSkipsNothing() throws Exception {
        try (HistoryDevice device = new HistoryDevice(port, testDevices, n1)) {
            device.chats.put(A, List.of(HistoryDevice.msg(A, false, 100, 1, "very old")));
            importAll(null, null);

            runner.tick().get(10, SECONDS);

            assertThat(fetched(device)).containsExactly(A);
        }
    }

    @Test
    void aFetchWhereEveryChatIsInactiveCompletes() throws Exception {
        try (HistoryDevice device = new HistoryDevice(port, testDevices, n1)) {
            device.chats.put(A, List.of(HistoryDevice.msg(A, false, 100, 1, "old")));
            WaWebHistoryImport imp = importSince(FROM);

            runner.tick().get(10, SECONDS);

            assertThat(reload(imp).getStatus()).isEqualTo(HistoryImportStatus.COMPLETED);
            assertThat(device.requests("fetchChatHistory")).isEmpty();
        }
    }

    /** A page that says it loaded more, for ever: nothing WhatsApp returns may hold the import. */
    private static Envelope alwaysLoading(Envelope req) {
        ObjectNode result = MAPPER.createObjectNode();
        result.putArray("messages");
        result.putNull("oldest");
        result.put("hasMore", true).put("loaded", true);
        return Envelope.response(req.id(), result);
    }

    @Test
    void idlePagesFromAnEarlierPassStillCount() throws Exception {
        try (HistoryDevice device = new HistoryDevice(port, testDevices, n1)) {
            device.chats.put(A, List.of());
            device.override = req -> "fetchChatHistory".equals(req.method()) ? alwaysLoading(req) : device.answer(req);
            WaWebHistoryImport imp = importChats("919811100001");
            WaWebHistoryImportChat item = itemsOf(imp).get(0);
            item.setIdlePages(3);
            items.save(item);

            runner.tick().get(10, SECONDS);

            assertThat(device.requests("fetchChatHistory")).hasSize(1);
            assertThat(itemsOf(imp).get(0).getStatus()).isEqualTo(HistoryChatStatus.DONE);
        }
    }

    @Test
    void aChatThatNeverStopsLoadingEndsAndTheNextOneRuns() throws Exception {
        try (HistoryDevice device = new HistoryDevice(port, testDevices, n1)) {
            device.chats.put(A, List.of());
            device.chats.put(B, List.of(HistoryDevice.msg(B, false, 150, 1, "b1")));
            device.override = req -> "fetchChatHistory".equals(req.method())
                    && A.equals(req.params().path("chatId").asString()) ? alwaysLoading(req) : device.answer(req);
            WaWebHistoryImport imp = importChats("919811100001", "919811100002");

            runner.tick().get(10, SECONDS);

            WaWebHistoryImportChat stuck = itemsOf(imp).stream().filter(i -> A.equals(i.getRemoteId())).findFirst()
                    .orElseThrow();
            assertThat(stuck.getStatus()).isEqualTo(HistoryChatStatus.DONE);
            assertThat(stuck.isOlderUnavailable()).isTrue();
            assertThat(device.requests("fetchChatHistory").stream()
                    .filter(e -> A.equals(e.params().path("chatId").asString()))).hasSize(4);
            assertThat(reload(imp).getStatus()).isEqualTo(HistoryImportStatus.COMPLETED);
            assertThat(messages.findAll()).hasSize(1);
        }
    }
}
