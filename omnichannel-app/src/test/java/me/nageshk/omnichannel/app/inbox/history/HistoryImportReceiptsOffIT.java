package me.nageshk.omnichannel.app.inbox.history;

import static java.util.concurrent.TimeUnit.SECONDS;
import static me.nageshk.omnichannel.app.inbox.history.HistoryImportRunnerIT.A;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import me.nageshk.omnichannel.db.inbox.HistoryImportStatus;
import me.nageshk.omnichannel.db.inbox.MessageStatus;
import me.nageshk.omnichannel.db.inbox.WaWebHistoryImport;
import me.nageshk.omnichannel.db.inbox.WaWebMessage;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.node.ObjectNode;

/** {@code omnichannel.history.receipts=false}: a fetch keeps the ticks it read and asks for no receipt times. */
@TestPropertySource(properties = "omnichannel.history.receipts=false")
class HistoryImportReceiptsOffIT extends HistoryImportTestBase {

    @Test
    void receiptsOffMakesNoCall() throws Exception {
        try (HistoryDevice device = new HistoryDevice(port, testDevices, n1)) {
            ObjectNode out = HistoryDevice.msg(A, true, 1_700_000_000L, 3, "seen");
            device.chats.put(A, List.of(out));
            device.receipt(out, 1_700_000_100L, 1_700_000_200L, null);
            WaWebHistoryImport imp = importAll(null, null);

            runner.tick().get(10, SECONDS);

            assertThat(reload(imp).getStatus()).isEqualTo(HistoryImportStatus.COMPLETED);
            WaWebMessage row = messages.findAll().getFirst();
            assertThat(row.getStatus()).isEqualTo(MessageStatus.READ);
            assertThat(row.getStatusTimes()).isNull();
            assertThat(device.requests("messageInfo")).isEmpty();
            assertThat(mongo.count(new Query(Criteria.where("eventKey").regex("^status:")), "channelWebhookEvents"))
                    .isZero();
        }
    }
}
