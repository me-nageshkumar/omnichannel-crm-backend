package me.nageshk.omnichannel.service.inbox.history;

import org.bson.Document;
import me.nageshk.omnichannel.db.inbox.WaWebHistoryImport;
import me.nageshk.omnichannel.db.inbox.WaWebHistoryImportChat;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/** Recounts an import's chats by status and stores the totals on the import. */
@Component
public class HistoryImportCounter {

    private final MongoTemplate mongo;

    HistoryImportCounter(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    public WaWebHistoryImport.Counts refresh(String importId) {
        Aggregation agg = Aggregation.newAggregation(Aggregation.match(Criteria.where("importId").is(importId)),
                Aggregation.group("status").count().as("n").sum("counts.seen").as("seen")
                        .sum("counts.inserted").as("inserted").sum("counts.updated").as("updated"));
        int total = 0;
        int done = 0;
        int skipped = 0;
        int failed = 0;
        long seen = 0;
        long inserted = 0;
        long updated = 0;
        for (Document d : mongo.aggregate(agg, WaWebHistoryImportChat.class, Document.class)) {
            int n = d.getInteger("n");
            total += n;
            String status = d.getString("_id");
            if ("DONE".equals(status)) done = n;
            else if ("SKIPPED".equals(status)) skipped = n;
            else if ("FAILED".equals(status)) failed = n;
            seen += number(d, "seen");
            inserted += number(d, "inserted");
            updated += number(d, "updated");
        }
        WaWebHistoryImport.Counts counts = new WaWebHistoryImport.Counts(total, done, skipped, failed, seen, inserted,
                updated);
        mongo.updateFirst(new Query(Criteria.where("_id").is(importId)), new Update().set("counts", counts),
                WaWebHistoryImport.class);
        return counts;
    }

    private static long number(Document d, String key) {
        return d.get(key) instanceof Number x ? x.longValue() : 0;
    }
}
