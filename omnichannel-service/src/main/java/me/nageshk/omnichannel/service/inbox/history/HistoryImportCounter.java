package me.nageshk.omnichannel.service.inbox.history;

import java.util.HashMap;
import java.util.Map;
import org.bson.Document;
import me.nageshk.omnichannel.db.inbox.WaWebHistoryImport;
import me.nageshk.omnichannel.db.inbox.WaWebHistoryImportChat;
import me.nageshk.omnichannel.db.inbox.WaWebHistoryImportChatRepository;
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
    private final WaWebHistoryImportChatRepository items;

    HistoryImportCounter(MongoTemplate mongo, WaWebHistoryImportChatRepository items) {
        this.mongo = mongo;
        this.items = items;
    }

    public WaWebHistoryImport.Counts refresh(String importId) {
        Map<String, Integer> n = new HashMap<>();
        Aggregation agg = Aggregation.newAggregation(Aggregation.match(Criteria.where("importId").is(importId)),
                Aggregation.group("status").count().as("n"));
        for (Document d : mongo.aggregate(agg, WaWebHistoryImportChat.class, Document.class)) {
            n.put(d.getString("_id"), d.getInteger("n"));
        }
        long seen = 0;
        long inserted = 0;
        long updated = 0;
        for (WaWebHistoryImportChat i : mongo.find(new Query(Criteria.where("importId").is(importId)),
                WaWebHistoryImportChat.class)) {
            if (i.getCounts() == null) continue;
            seen += i.getCounts().seen();
            inserted += i.getCounts().inserted();
            updated += i.getCounts().updated();
        }
        WaWebHistoryImport.Counts counts = new WaWebHistoryImport.Counts((int) items.countByImportId(importId),
                n.getOrDefault("DONE", 0), n.getOrDefault("SKIPPED", 0), n.getOrDefault("FAILED", 0), seen, inserted,
                updated);
        mongo.updateFirst(new Query(Criteria.where("_id").is(importId)), new Update().set("counts", counts),
                WaWebHistoryImport.class);
        return counts;
    }
}
