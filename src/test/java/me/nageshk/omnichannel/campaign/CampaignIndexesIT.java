package me.nageshk.omnichannel.campaign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.springframework.data.mongodb.core.query.Query;

@SpringBootTest
class CampaignIndexesIT {

    @Autowired MongoTemplate mongo;

    @BeforeEach
    void clean() {
        for (Class<?> c : List.of(CampaignSender.class, CampaignRecipient.class)) mongo.remove(new Query(), c);
    }

    private CampaignRecipient recipient(String campaignId, String phone) {
        CampaignRecipient r = new CampaignRecipient();
        r.setCampaignId(campaignId);
        r.setWorkspaceId("w1");
        r.setPhone(phone);
        r.setStatus(RecipientStatus.PENDING);
        return r;
    }

    @Test
    void recipientPhoneIsUniquePerCampaignWhenSet() {
        mongo.save(recipient("c1", "919811111111"));
        mongo.save(recipient("c2", "919811111111"));
        assertThatThrownBy(() -> mongo.save(recipient("c1", "919811111111"))).isInstanceOf(DuplicateKeyException.class);
        assertThatCode(() -> {
            mongo.save(recipient("c1", null));
            mongo.save(recipient("c1", null));
        }).doesNotThrowAnyException();
    }

    @Test
    void indexesAreNamedAsDesigned() {
        assertThat(names(CampaignRecipient.class)).contains("campaign_phone", "campaign_status", "ws_phone_sent",
                "sender_sent", "sender_attempted", "message", "chat_sent", "status_attempted");
        IndexInfo stuck = mongo.indexOps(CampaignRecipient.class).getIndexInfo().stream()
                .filter(i -> i.getName().equals("status_attempted")).findFirst().orElseThrow();
        assertThat(stuck.getPartialFilterExpression()).contains("SENDING");
        assertThat(mongo.indexOps(CampaignRecipient.class).getIndexInfo().stream()
                .filter(i -> i.getName().equals("campaign_phone")).findFirst().orElseThrow().isUnique()).isTrue();
        IndexInfo phone = mongo.indexOps(CampaignSender.class).getIndexInfo().stream()
                .filter(i -> i.getIndexFields().get(0).getKey().equals("phoneNumberId")).findFirst().orElseThrow();
        assertThat(phone.isUnique()).isTrue();
    }

    @Test
    void campaignIndexesAreNamedAndNameIsUniquePerWorkspace() {
        assertThat(names(Campaign.class)).contains("ws_nameKey", "ws_status");
        assertThat(mongo.indexOps(Campaign.class).getIndexInfo().stream()
                .filter(i -> i.getName().equals("ws_nameKey")).findFirst().orElseThrow().isUnique()).isTrue();
    }

    private List<String> names(Class<?> type) {
        return mongo.indexOps(type).getIndexInfo().stream().map(IndexInfo::getName).toList();
    }
}
