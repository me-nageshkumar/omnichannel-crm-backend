package me.nageshk.omnichannel.app.inbox.history;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import me.nageshk.omnichannel.service.inbox.history.HistoryDeviceClient;
import me.nageshk.omnichannel.service.inbox.history.HistoryDeviceClient.FetchRequest;
import me.nageshk.omnichannel.service.ws.DeviceRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Smoke test of the harness: the fake device answers the two history methods the way spec section 3 says. */
class HistoryDeviceIT extends HistoryImportTestBase {

    @Autowired HistoryDeviceClient client;
    @Autowired DeviceRegistry registry;

    @Test
    void fakeAnswersListChatsAndFetchChatHistory() throws Exception {
        String remote = "919811100001@c.us";
        try (HistoryDevice device = new HistoryDevice(port, testDevices, n1)) {
            device.chats.put(remote, List.of(HistoryDevice.msg(remote, false, 100, 1, "a"),
                    HistoryDevice.msg(remote, true, 200, 2, "b"), HistoryDevice.msg(remote, false, 300, 1, "c")));
            var session = registry.find(n1.getId()).orElseThrow();

            var rows = client.listChats(session).get(2, SECONDS);
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).id()).isEqualTo(remote);
            assertThat(rows.get(0).t()).isEqualTo(300L);

            var page = client.fetchChatHistory(session, new FetchRequest(remote, null, List.of(), null, null, 2, 20))
                    .get(2, SECONDS);
            assertThat(page.messages()).hasSize(2);
            assertThat(page.messages().get(0).path("t").asLong()).isEqualTo(200L);
            assertThat(page.messages().get(1).path("t").asLong()).isEqualTo(300L);
            assertThat(page.hasMore()).isTrue();
            assertThat(page.oldestT()).isEqualTo(200L);
            assertThat(device.requests("fetchChatHistory")).hasSize(1);
        }
    }
}
