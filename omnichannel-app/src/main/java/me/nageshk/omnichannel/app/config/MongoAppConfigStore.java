package me.nageshk.omnichannel.app.config;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.exists;
import static com.mongodb.client.model.Filters.in;
import static com.mongodb.client.model.Filters.or;
import static com.mongodb.client.model.Filters.regex;
import static com.mongodb.client.model.Updates.set;

import com.mongodb.ConnectionString;
import com.mongodb.ErrorCategory;
import com.mongodb.MongoClientSettings;
import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.UpdateOptions;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.bson.Document;

/** The {@code appConfig} collection, one document {@code _id: "app"}, read before the application context exists. */
final class MongoAppConfigStore implements AppConfigStore {

    static final String COLLECTION = "appConfig";
    static final String ID = "app";

    private final MongoClient client;
    private final MongoCollection<Document> collection;
    private final boolean ownsClient;

    MongoAppConfigStore(MongoClient client, String database, boolean ownsClient) {
        this.client = client;
        this.collection = client.getDatabase(database).getCollection(COLLECTION);
        this.ownsClient = ownsClient;
    }

    /** A short-lived client for startup: a few seconds to reach the server, so a stopped Mongo cannot hang the start. */
    static MongoAppConfigStore connect(String uri) {
        ConnectionString cs = new ConnectionString(uri);
        MongoClientSettings settings = MongoClientSettings.builder()
                .applyConnectionString(cs)
                .applyToClusterSettings(b -> b.serverSelectionTimeout(3, TimeUnit.SECONDS))
                .applyToSocketSettings(b -> b.connectTimeout(3, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS))
                .build();
        String db = cs.getDatabase() == null ? "test" : cs.getDatabase();
        return new MongoAppConfigStore(MongoClients.create(settings), db, true);
    }

    @Override
    public Map<String, Object> read() {
        Document doc = collection.find(eq("_id", ID)).first();
        Map<String, Object> out = new LinkedHashMap<>();
        if (doc != null) doc.forEach((k, v) -> {
            if (!"_id".equals(k)) out.put(k, v);
        });
        return out;
    }

    @Override
    public void saveIfAbsent(String field, Object value) {
        try {
            // absent, null, an empty list or blank text all count as not set
            collection.updateOne(
                    and(eq("_id", ID), or(exists(field, false), in(field, null, List.of()), regex(field, "^\\s*$"))),
                    set(field, value), new UpdateOptions().upsert(true));
        } catch (MongoWriteException e) {
            // the document exists and already has the field: the upsert's insert clashes on _id, which is what we want
            if (e.getError().getCategory() != ErrorCategory.DUPLICATE_KEY) throw e;
        }
    }

    @Override
    public void save(String field, Object value) {
        collection.updateOne(eq("_id", ID), set(field, value), new UpdateOptions().upsert(true));
    }

    @Override
    public void close() {
        if (ownsClient) client.close();
    }
}
