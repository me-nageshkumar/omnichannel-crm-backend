package me.nageshk.omnichannel.campaign;

import java.util.HashMap;
import java.util.Map;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/** Recounts a campaign's recipients by status and stores the totals on the campaign. */
@Component
public class CampaignCounter {

    private final MongoTemplate mongo;

    CampaignCounter(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    public Campaign.Counts refresh(String campaignId) {
        Map<String, Integer> n = new HashMap<>();
        Aggregation agg = Aggregation.newAggregation(Aggregation.match(Criteria.where("campaignId").is(campaignId)),
                Aggregation.group("status").count().as("n"));
        for (Document d : mongo.aggregate(agg, CampaignRecipient.class, Document.class)) {
            n.put(d.getString("_id"), d.getInteger("n"));
        }
        int replied = (int) mongo.count(new Query(Criteria.where("campaignId").is(campaignId).and("repliedAt").ne(null)),
                CampaignRecipient.class);
        int sending = n.getOrDefault("SENDING", 0);
        int sent = n.getOrDefault("SENT", 0);
        int delivered = n.getOrDefault("DELIVERED", 0);
        int read = n.getOrDefault("READ", 0);
        int total = n.values().stream().mapToInt(Integer::intValue).sum();
        Campaign.Counts counts = new Campaign.Counts(total, n.getOrDefault("PENDING", 0) + sending,
                sent + delivered + read, delivered + read, read, replied, n.getOrDefault("SKIPPED", 0),
                n.getOrDefault("FAILED", 0));
        mongo.updateFirst(new Query(Criteria.where("_id").is(campaignId)), new Update().set("counts", counts),
                Campaign.class);
        return counts;
    }
}
