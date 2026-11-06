package me.nageshk.omnichannel.app.inbox.history;

import static java.util.concurrent.TimeUnit.SECONDS;
import static me.nageshk.omnichannel.app.inbox.history.HistoryImportRunnerIT.A;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import me.nageshk.omnichannel.db.inbox.WaWebMessage;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** {@code omnichannel.history.receipts-batch=2}: a page's receipts are asked for two ids at a time. */
@TestPropertySource(properties = "omnichannel.history.receipts-batch=2")
class HistoryImportReceiptsBatchIT extends HistoryImportTestBase {

    @Test
    void threeWantedMessagesTakeTwoCalls() throws Exception {
        try (HistoryDevice device = new HistoryDevice(port, testDevices, n1)) {
            List<ObjectNode> ours = List.of(HistoryDevice.msg(A, true, 1_700_000_000L, 2, "one"),
                    HistoryDevice.msg(A, true, 1_700_000_010L, 2, "two"),
                    HistoryDevice.msg(A, true, 1_700_000_020L, 2, "three"));
            device.chats.put(A, ours);
            for (ObjectNode m : ours) device.receipt(m, m.get("t").asLong() + 5, null, null);
            importAll(null, null);

            runner.tick().get(10, SECONDS);

            assertThat(device.requests("messageInfo")).extracting(r -> r.params().path("ids").size())
                    .containsExactly(2, 1);
            assertThat(device.requests("messageInfo").stream()
                    .flatMap(r -> r.params().path("ids").valueStream().map(JsonNode::asString)).toList())
                    .containsExactlyInAnyOrderElementsOf(ours.stream().map(HistoryDevice::id).toList());
            assertThat(messages.findAll()).extracting(WaWebMessage::getStatusTimes)
                    .allSatisfy(t -> assertThat(t.getDeliveredAt()).isNotNull());
        }
    }
}
