package me.nageshk.omnichannel.app.inbox.history;

import static java.util.concurrent.TimeUnit.SECONDS;
import static me.nageshk.omnichannel.app.inbox.history.HistoryImportRunnerIT.A;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import me.nageshk.omnichannel.db.inbox.HistoryChatStatus;
import me.nageshk.omnichannel.db.inbox.HistoryImportStatus;
import me.nageshk.omnichannel.db.inbox.WaWebHistoryImport;
import me.nageshk.omnichannel.db.inbox.WaWebHistoryImportChat;
import me.nageshk.omnichannel.db.inbox.WaWebMessage;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/** A per-chat cap of three messages. */
@TestPropertySource(properties = "omnichannel.history.max-messages-per-chat=3")
class HistoryImportRunnerCapIT extends HistoryImportTestBase {

    @Test
    void capStopsAChat() throws Exception {
        try (HistoryDevice device = new HistoryDevice(port, testDevices, n1)) {
            device.chats.put(A, List.of(HistoryDevice.msg(A, false, 100, 1, "m1"),
                    HistoryDevice.msg(A, false, 200, 1, "m2"), HistoryDevice.msg(A, false, 300, 1, "m3"),
                    HistoryDevice.msg(A, false, 400, 1, "m4"), HistoryDevice.msg(A, false, 500, 1, "m5")));
            WaWebHistoryImport imp = importChats("919811100001");

            runner.tick().get(10, SECONDS);

            WaWebHistoryImportChat item = itemsOf(imp).get(0);
            assertThat(item.getStatus()).isEqualTo(HistoryChatStatus.DONE);
            assertThat(item.isCapped()).isTrue();
            assertThat(item.getCounts().seen()).isEqualTo(3);
            // the newest three: the walk goes backwards from now
            assertThat(messages.findAll()).extracting(WaWebMessage::getBody)
                    .containsExactlyInAnyOrder("m3", "m4", "m5");
            assertThat(reload(imp).getStatus()).isEqualTo(HistoryImportStatus.COMPLETED);
        }
    }
}
