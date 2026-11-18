package me.nageshk.omnichannel.app.inbox.history;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import me.nageshk.omnichannel.db.inbox.HistoryChatStatus;
import me.nageshk.omnichannel.db.inbox.WaWebHistoryImport;
import me.nageshk.omnichannel.db.inbox.WaWebHistoryImportChat;
import me.nageshk.omnichannel.db.inbox.WaWebMessage;
import me.nageshk.omnichannel.db.phone.PhoneNumberRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** A number that moved to a new brand imports only what the phone holds from the move on (spec §7.2). */
class HistoryImportAfterMoveIT extends HistoryImportTestBase {

    static final String RAVI = "919811100001@c.us";
    static final String VEENU = "919811100002@c.us";
    static final long MOVED = 1_700_003_000L;

    @Autowired PhoneNumberRepository numbers;

    @Test
    void historyImportAfterMoveTakesOnlyNewMessages() throws Exception {
        n1.setMovedInAt(Instant.ofEpochSecond(MOVED));
        numbers.save(n1);
        try (HistoryDevice device = new HistoryDevice(port, testDevices, n1)) {
            device.chats.put(RAVI, List.of(HistoryDevice.msg(RAVI, false, 1_700_001_000L, 0, "r1"),
                    HistoryDevice.msg(RAVI, false, 1_700_002_000L, 0, "r2")));
            device.chats.put(VEENU, List.of(HistoryDevice.msg(VEENU, false, 1_700_001_500L, 0, "v-before"),
                    HistoryDevice.msg(VEENU, false, 1_700_005_000L, 0, "v-after")));
            WaWebHistoryImport imp = importAll(null, null);

            runner.tick().get(10, SECONDS);

            assertThat(messages.findAll()).extracting(WaWebMessage::getBody).containsExactly("v-after");
            assertThat(chats.findAll()).extracting(c -> c.getRemoteId()).containsExactly(VEENU);
            List<WaWebHistoryImportChat> items = itemsOf(imp);
            assertThat(items).hasSize(2);
            assertThat(items.stream().filter(i -> RAVI.equals(i.getRemoteId())).findFirst().orElseThrow().getStatus())
                    .isEqualTo(HistoryChatStatus.SKIPPED);
            assertThat(items.stream().filter(i -> VEENU.equals(i.getRemoteId())).findFirst().orElseThrow().getStatus())
                    .isEqualTo(HistoryChatStatus.DONE);
        }
    }

    @Test
    void aRangeStartingBeforeTheMoveIsClampedToIt() throws Exception {
        n1.setMovedInAt(Instant.ofEpochSecond(MOVED));
        numbers.save(n1);
        try (HistoryDevice device = new HistoryDevice(port, testDevices, n1)) {
            device.chats.put(RAVI, List.of(HistoryDevice.msg(RAVI, false, 1_700_001_000L, 0, "r1")));
            // the range starts before the move; the move still bounds it
            importAll(Instant.ofEpochSecond(1_700_000_000L), null);

            runner.tick().get(10, SECONDS);

            assertThat(messages.count()).isZero();
        }
    }
}
