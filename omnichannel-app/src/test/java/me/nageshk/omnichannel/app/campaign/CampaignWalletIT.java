package me.nageshk.omnichannel.app.campaign;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.nageshk.omnichannel.app.support.TestBilling;
import me.nageshk.omnichannel.db.billing.WalletRepository;
import me.nageshk.omnichannel.db.campaign.Campaign;
import me.nageshk.omnichannel.db.campaign.CampaignRecipient;
import me.nageshk.omnichannel.db.campaign.CampaignStatus;
import me.nageshk.omnichannel.db.campaign.PausedBy;
import me.nageshk.omnichannel.db.campaign.RecipientStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpMethod;

/** A campaign stops, with nobody failed, when the wallet cannot pay for the next message. */
class CampaignWalletIT extends CampaignTestBase {

    @Autowired TestBilling billing;
    @Autowired WalletRepository wallets;

    @BeforeEach
    void noPlan() {
        billing.reset();
    }

    @AfterEach
    void leaveNoPlan() {
        billing.reset();
    }

    private long count(String campaignId, RecipientStatus status) {
        return mongo.count(new Query(Criteria.where("campaignId").is(campaignId).and("status").is(status)),
                CampaignRecipient.class);
    }

    private void ticks(int n) throws Exception {
        for (int i = 0; i < n; i++) {
            tickNow();
            clock.advance(Duration.ofSeconds(45));
        }
    }

    @Test
    void aWalletThatCoversTwoOfFiveSendsTwoAndPausesTheCampaign() throws Exception {
        billing.assign(acme.getId(), Set.of("campaigns"), Map.of());
        billing.messageRate(acme.getId(), "0.25");
        billing.wallet(acme.getId(), "0.5000");
        try (AutoDevice d = device(n1)) {
            String id = startedFilterCampaign("Diwali", List.of("919811100001", "919811100002", "919811100003",
                    "919811100004", "919811100005"), n1);
            ticks(4);

            assertThat(d.requests("sendMessage")).hasSize(2);
            Campaign c = campaign(id);
            assertThat(c.getStatus()).isEqualTo(CampaignStatus.PAUSED);
            assertThat(c.getPausedBy()).isEqualTo(PausedBy.SYSTEM);
            assertThat(c.getPauseReason()).isEqualTo("WALLET_EMPTY");
            assertThat(count(id, RecipientStatus.SENT)).isEqualTo(2);
            assertThat(count(id, RecipientStatus.PENDING)).isEqualTo(3);
            assertThat(count(id, RecipientStatus.FAILED)).isZero();
            assertThat(count(id, RecipientStatus.SENDING)).isZero();
            assertThat(wallets.findByCustomerId(acme.getId()).orElseThrow().getBalance()).isEqualByComparingTo("0");

            // money arrives and a person resumes it
            billing.wallet(acme.getId(), "10.0000");
            assertThat(api(HttpMethod.POST, "/campaigns/" + id + "/resume", manager, null).getStatusCode()
                    .is2xxSuccessful()).isTrue();
            ticks(4);

            assertThat(d.requests("sendMessage")).hasSize(5);
            assertThat(count(id, RecipientStatus.SENT)).isEqualTo(5);
            assertThat(wallets.findByCustomerId(acme.getId()).orElseThrow().getBalance()).isEqualByComparingTo("9.25");
        }
    }
}
