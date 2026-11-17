package me.nageshk.omnichannel.app.campaign;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import me.nageshk.omnichannel.app.support.TestBilling;
import me.nageshk.omnichannel.db.campaign.CampaignStatus;
import me.nageshk.omnichannel.db.campaign.RecipientStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** A running campaign waits while the customer's plan does not cover campaigns, and goes on when it does again. */
class CampaignPlanIT extends CampaignTestBase {

    static final String P1 = "919811100001";

    @Autowired TestBilling billing;

    @BeforeEach
    void noPlan() {
        billing.reset();
    }

    @AfterEach
    void leaveNoPlan() {
        billing.reset();
    }

    @Test
    void aCampaignWaitsForThePlanAndThenSends() throws Exception {
        try (AutoDevice d = device(n1)) {
            String id = startedFilterCampaign("Diwali", List.of(P1), n1);

            billing.assign(acme.getId(), Set.of("automations"), Map.of());
            tickNow();
            assertThat(d.requests("sendMessage")).isEmpty();
            assertThat(campaign(id).getStatus()).isEqualTo(CampaignStatus.RUNNING);
            assertThat(recipient(id, P1).getStatus()).isNotEqualTo(RecipientStatus.SENT)
                    .isNotEqualTo(RecipientStatus.FAILED);

            billing.assign(acme.getId(), Set.of("campaigns"), Map.of());
            tickNow();
            assertThat(d.requests("sendMessage")).hasSize(1);
            assertThat(recipient(id, P1).getStatus()).isEqualTo(RecipientStatus.SENT);
        }
    }
}
