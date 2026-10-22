package me.nageshk.omnichannel.campaign;

import static org.assertj.core.api.Assertions.assertThat;

import com.mongodb.client.MongoClient;
import java.util.List;
import java.util.Map;
import me.nageshk.omnichannel.contact.Contact;
import me.nageshk.omnichannel.contact.ContactLabel;
import me.nageshk.omnichannel.support.MongoTestCommands;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

/** A materialisation over its time limit answers 504 and leaves the campaign a draft. */
@TestPropertySource(properties = "omnichannel.contacts.query-timeout.materialise=1ms")
@Import(MongoTestCommands.class)
class CampaignAdvancedTimeoutIT extends CampaignTestBase {

    @Autowired MongoClient client;

    @AfterEach
    void failpointOff() {
        MongoTestCommands.alwaysTimeOut(client, false);
    }

    @Test
    void startTimeoutIs504AndLeavesTheDraft() {
        ContactLabel vip = label("VIP");
        Contact a = contact("A", "919800000001");
        a.getLabels().add(vip.getId());
        mongo.save(a);
        String f = advancedFilter("VIPs", Map.of("combinator", "and", "not", false, "rules",
                List.of(Map.of("field", "labels", "operator", "has", "value", vip.getId()))));
        String t = templateNamed("Adv Hi", "Hi");
        String id = api(HttpMethod.POST, "/campaigns", manager, filterCampaign("Adv", f, List.of(t), List.of(),
                List.of(n1.getId()))).getBody().get("id").asString();

        MongoTestCommands.alwaysTimeOut(client, true);
        ResponseEntity<JsonNode> res = api(HttpMethod.POST, "/campaigns/" + id + "/start", manager, null);
        MongoTestCommands.alwaysTimeOut(client, false);

        assertThat(res.getStatusCode().value()).isEqualTo(504);
        assertThat(res.getBody().get("code").asString()).isEqualTo("QUERY_TIMEOUT");
        assertThat(res.getBody().get("message").asString())
                .isEqualTo("This filter takes too long to run. Make it more specific.");
        assertThat(campaign(id).getStatus()).isEqualTo(CampaignStatus.DRAFT);
        assertThat(mongo.count(new Query(Criteria.where("campaignId").is(id)), CampaignRecipient.class)).isZero();

        ResponseEntity<JsonNode> again = api(HttpMethod.POST, "/campaigns/" + id + "/start", manager, null);
        assertThat(again.getStatusCode().value()).isEqualTo(200);
        assertThat(again.getBody().get("status").asString()).isEqualTo("RUNNING");
    }
}
