package me.nageshk.omnichannel.app.inbox.history;

import static java.util.concurrent.TimeUnit.SECONDS;
import static me.nageshk.omnichannel.app.inbox.history.HistoryImportRunnerIT.A;
import static me.nageshk.omnichannel.app.inbox.history.HistoryImportRunnerIT.B;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import me.nageshk.omnichannel.db.inbox.HistoryImportStatus;
import me.nageshk.omnichannel.db.inbox.WaWebHistoryImport;
import me.nageshk.omnichannel.service.inbox.InboxEvents;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;

/** What the import publishes: one {@code ChatChanged} per finished chat and nothing else. */
@Import(HistoryImportRunnerEventsIT.RecorderConfig.class)
class HistoryImportRunnerEventsIT extends HistoryImportTestBase {

    static class Recorder {
        final List<Object> events = new CopyOnWriteArrayList<>();

        @EventListener
        void on(InboxEvents.ChatCreated e) {
            events.add(e);
        }

        @EventListener
        void on(InboxEvents.ChatReopened e) {
            events.add(e);
        }

        @EventListener
        void on(InboxEvents.MessageUpserted e) {
            events.add(e);
        }

        @EventListener
        void on(InboxEvents.ChatChanged e) {
            events.add(e);
        }

        @EventListener
        void on(InboxEvents.ActionRecorded e) {
            events.add(e);
        }
    }

    @TestConfiguration
    static class RecorderConfig {
        @Bean
        Recorder historyRecorder() {
            return new Recorder();
        }
    }

    @Autowired Recorder recorder;

    @Test
    void finishedChatPublishesChatChangedOnce() throws Exception {
        try (HistoryDevice device = new HistoryDevice(port, testDevices, n1)) {
            HistoryImportRunnerIT.seedTwoChats(device);
            WaWebHistoryImport imp = importAll(null, null);
            recorder.events.clear();

            runner.tick().get(10, SECONDS);

            assertThat(reload(imp).getStatus()).isEqualTo(HistoryImportStatus.COMPLETED);
            assertThat(recorder.events).hasSize(2).allMatch(e -> e instanceof InboxEvents.ChatChanged);
            assertThat(recorder.events).extracting(e -> ((InboxEvents.ChatChanged) e).chat().getRemoteId())
                    .containsExactlyInAnyOrder(A, B);
        }
    }
}
