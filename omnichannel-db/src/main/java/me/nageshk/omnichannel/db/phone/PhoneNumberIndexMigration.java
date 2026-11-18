package me.nageshk.omnichannel.db.phone;

import org.bson.Document;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.springframework.data.mongodb.core.index.IndexOperations;
import org.springframework.stereotype.Component;

/**
 * An archive (a number that moved to another brand) has no {@code number}, so the plain unique index on it would
 * refuse the second archive. This replaces it with {@code number_unique}, unique over strings only. Runs at every
 * start and does nothing once the index is right.
 */
@Component
@Order(5)
public class PhoneNumberIndexMigration implements ApplicationRunner {

    static final String COLLECTION = "phoneNumbers";
    static final String INDEX = "number_unique";

    private final MongoTemplate mongo;

    public PhoneNumberIndexMigration(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public void run(ApplicationArguments args) {
        ensureIndex();
    }

    /** Drops any other index on {@code number} alone, then creates the partial unique one. */
    public void ensureIndex() {
        IndexOperations ops = mongo.indexOps(COLLECTION);
        for (IndexInfo info : ops.getIndexInfo()) {
            boolean onNumberOnly = info.getIndexFields().size() == 1
                    && "number".equals(info.getIndexFields().get(0).getKey());
            if (onNumberOnly && !INDEX.equals(info.getName())) ops.dropIndex(info.getName());
        }
        ops.createIndex(new Index().named(INDEX).on("number", Sort.Direction.ASC).unique()
                .partial(org.springframework.data.mongodb.core.index.PartialIndexFilter.of(
                        new Document("number", new Document("$type", "string")))));
    }
}
