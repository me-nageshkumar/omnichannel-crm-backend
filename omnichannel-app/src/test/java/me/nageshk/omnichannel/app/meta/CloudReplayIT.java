package me.nageshk.omnichannel.app.meta;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import me.nageshk.omnichannel.common.events.IngestionFailedException;
import me.nageshk.omnichannel.db.inbox.WaWebMessage;
import me.nageshk.omnichannel.service.channel.cloud.CloudWebhookIngest;
import me.nageshk.omnichannel.service.channel.whatsapp.WhatsAppWebhookProcessor;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;

/** A Cloud event stored but not processed (a database failure mid-ingest, a restart) is processed again. */
class CloudReplayIT extends MetaTestBase {

    @MockitoSpyBean WhatsAppWebhookProcessor processor;
    @Autowired CloudWebhookIngest ingest;

    @Test
    void storedButUnprocessedEventIsReplayed() throws Exception {
        mongo.remove(new Query(), "channelWebhookEvents");
        JsonNode connection = connect("111");
        String numberId = addCloudNumber(connection.get("id").asString(), "Admissions", "333", "+91 98765 43210")
                .get("id").asString();
        Mockito.doThrow(new IngestionFailedException("cloud", "wamid.R1", new RuntimeException("database down")))
                .doCallRealMethod()
                .when(processor).process(org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(me.nageshk.omnichannel.common.channel.whatsapp.webhook.WebhookEvent.class));

        webhook(keyOf(connection), envelope(textChange("333", "919876543210", "919799750743", "wamid.R1", "Hello")));
        Thread.sleep(500);
        assertThat(mongo.find(new Query(Criteria.where("phoneNumberId").is(numberId)), WaWebMessage.class)).isEmpty();

        assertThat(ingest.replay(Duration.ZERO)).isEqualTo(1);

        assertThat(mongo.find(new Query(Criteria.where("phoneNumberId").is(numberId)), WaWebMessage.class)).hasSize(1);
        assertThat(ingest.replay(Duration.ZERO)).isZero();
    }
}
