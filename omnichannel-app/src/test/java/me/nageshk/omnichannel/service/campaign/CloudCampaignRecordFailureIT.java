package me.nageshk.omnichannel.service.campaign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;

import me.nageshk.omnichannel.app.meta.CloudCampaignTestBase;
import me.nageshk.omnichannel.db.campaign.Campaign;
import me.nageshk.omnichannel.db.campaign.CampaignStatus;
import me.nageshk.omnichannel.db.campaign.RecipientStatus;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** A send Meta accepted but we could not record is never sent again (final review I2). */
class CloudCampaignRecordFailureIT extends CloudCampaignTestBase {

    @MockitoSpyBean RecipientClaims claims;

    @Test
    void failureToRecordAnAcceptedSendIsNotResent() throws Exception {
        String t = syncTemplate(FOLLOW_UP);
        String id = cloudCampaign("Record failure", t, java.util.List.of("919633300001"), nameIntoBody1(), numberId);
        start(id);
        doThrow(new IllegalStateException("database went away")).when(claims).sent(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        GRAPH.enqueue("POST /333/messages", 200, sent("wamid.R1"));
        GRAPH.enqueue("POST /333/messages", 200, sent("wamid.R2"));
        tickNow();
        assertThat(recipients(id).get(0).getStatus()).isEqualTo(RecipientStatus.SENDING);
        assertThat(mongo.findById(id, Campaign.class).getStatus()).isEqualTo(CampaignStatus.RUNNING);
        tickNow();
        assertThat(GRAPH.recorded("POST /333/messages")).hasSize(1);
    }
}
