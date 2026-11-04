package me.nageshk.omnichannel.db.config;

import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.springframework.stereotype.Component;

/**
 * Turns the contact's single {@code waId} into the {@code waIds} list and drops the old unique {@code ws_waId} index.
 *
 * <p>Run from {@link CollectionRenames#afterSingletonsInstantiated()}, so after the collection renames and before the
 * web server and the seeders start. Spring Data has already created the new {@code ws_waIds} index by then; it only
 * covers documents that have a string in {@code waIds}, so documents still holding {@code waId} do not collide with
 * it. Idempotent: documents that already have {@code waIds} are not touched.
 */
@Slf4j
@Component
public class ContactIdsMigration {

    static final String COLLECTION = "contacts";
    static final String OLD_INDEX = "ws_waId";

    private final MongoTemplate mongo;

    public ContactIdsMigration(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    public void migrate() {
        if (!mongo.collectionExists(COLLECTION)) return;
        Bson filter = Filters.and(Filters.exists("waId"), Filters.exists("waIds", false));
        long changed = mongo.getCollection(COLLECTION).updateMany(filter,
                List.of(Document.parse("{$set: {waIds: [\"$waId\"]}}"), Document.parse("{$unset: \"waId\"}")))
                .getModifiedCount();
        if (changed > 0) log.info("contacts: moved waId into waIds on {} documents", changed);
        for (IndexInfo index : mongo.indexOps(COLLECTION).getIndexInfo()) {
            if (OLD_INDEX.equals(index.getName())) {
                mongo.indexOps(COLLECTION).dropIndex(OLD_INDEX);
                log.info("contacts: dropped the old index {}", OLD_INDEX);
            }
        }
    }
}
