package me.nageshk.omnichannel.app.meta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doReturn;

import me.nageshk.omnichannel.service.channel.ChannelWebhookEventStore;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;

/** A database failure while storing Meta's event answers 500, so Meta delivers it again (own context: a spy). */
class MetaWebhookStoreFailureIT extends MetaTestBase {

    @MockitoSpyBean ChannelWebhookEventStore store;

    @Test
    void failedSaveAnswers500() {
        JsonNode connection = connect("111");
        addCloudNumber(connection.get("id").asString(), "Admissions", "333", "+91 98765 43210");
        doReturn(new ChannelWebhookEventStore.SaveResult(ChannelWebhookEventStore.Outcome.FAILED, null))
                .when(store).saveResult(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), anyInt());

        assertThat(webhook(keyOf(connection),
                envelope(textChange("333", "919876543210", "919799750743", "wamid.A", "Hi"))).getStatusCode())
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
