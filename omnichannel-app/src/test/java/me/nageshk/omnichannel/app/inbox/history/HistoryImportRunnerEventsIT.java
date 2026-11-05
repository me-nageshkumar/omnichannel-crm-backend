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
import org.springframework.test.context.TestPropertySource;

/** What the import publishes: for every page that stored something, {@code ChatChanged} then {@code HistoryStored}. */
@TestPropertySource(properties = "omnichannel.history.page-size=2")
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
        void on(InboxEvents.HistoryStored e) {
            events.add(e);
        }

        @EventListener
        void on(InboxEvents.ChatFetched e) {
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
    void everyPageThatStoredSomethingIsAnnounced() throws Exception {
        try (HistoryDevice device = new HistoryDevice(port, testDevices, n1)) {
            HistoryImportRunnerIT.seedTwoChats(device);
            WaWebHistoryImport imp = importAll(null, null);
            recorder.events.clear();

            runner.tick().get(10, SECONDS);

            assertThat(reload(imp).getStatus()).isEqualTo(HistoryImportStatus.COMPLETED);
            // A has three messages: two pages. B has two: one page. Each chat is announced fetched once at its end.
            assertThat(recorder.events).hasSize(8);
            assertThat(recorder.events.stream().filter(e -> e instanceof InboxEvents.ChatFetched)
                    .map(e -> ((InboxEvents.ChatFetched) e).chat().getRemoteId()))
                    .containsExactlyInAnyOrder(A, B);
            assertThat(recorder.events.stream().filter(e -> e instanceof InboxEvents.ChatChanged)
                    .map(e -> ((InboxEvents.ChatChanged) e).chat().getRemoteId()))
                    .containsExactlyInAnyOrder(A, A, B);
            assertThat(recorder.events.stream().filter(e -> e instanceof InboxEvents.HistoryStored)
                    .map(e -> ((InboxEvents.HistoryStored) e).chat().getRemoteId()))
                    .containsExactlyInAnyOrder(A, A, B);
        }
    }

    @Test
    void aSecondRunThatStoresNothingAnnouncesNothing() throws Exception {
        try (HistoryDevice device = new HistoryDevice(port, testDevices, n1)) {
            HistoryImportRunnerIT.seedTwoChats(device);
            importAll(null, null);
            runner.tick().get(10, SECONDS);
            WaWebHistoryImport again = importAll(null, null);
            recorder.events.clear();

            runner.tick().get(10, SECONDS);

            assertThat(reload(again).getStatus()).isEqualTo(HistoryImportStatus.COMPLETED);
            // a chat whose fetch ended is still announced fetched, so its contact can be looked up; nothing else
            assertThat(recorder.events).allMatch(e -> e instanceof InboxEvents.ChatFetched);
            assertThat(recorder.events.stream().map(e -> ((InboxEvents.ChatFetched) e).chat().getRemoteId()))
                    .containsExactlyInAnyOrder(A, B);
        }
    }
}
