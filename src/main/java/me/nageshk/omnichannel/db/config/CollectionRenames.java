package me.nageshk.omnichannel.db.config;

import com.mongodb.MongoNamespace;
import com.mongodb.client.model.RenameCollectionOptions;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexDefinition;
import org.springframework.data.mongodb.core.index.IndexResolver;
import org.springframework.data.mongodb.core.mapping.MongoPersistentEntity;
import org.springframework.stereotype.Component;

/**
 * Moves collections from their old snake_case names to the camelCase names the code uses.
 *
 * <p>Runs once all singletons exist (so Spring Data has already created the new, empty collections and their
 * indexes) and before the web server, the schedulers and the seeders start, so nothing writes in between. For each
 * old name that still exists: an empty or missing new collection is replaced by the renamed old one, keeping its
 * documents and indexes, and the code's indexes are created again; an empty old leftover next to a non-empty new
 * collection is dropped; two non-empty collections are left alone and logged. A fresh or already migrated database
 * is not touched. Then {@link ClassAliasRenames} brings the stored type hints up to the current package names.
 */
@Slf4j
@Component
public class CollectionRenames implements SmartInitializingSingleton {

    /** Old name → current name. Kept so that any database still on the old names migrates on its next start. */
    public static final Map<String, String> RENAMES = renames();

    /** Read only by {@code RoleSeeder} on very old databases; dropped here once it is empty. */
    static final String LEGACY_SYSTEM_STATE = "system_state";

    private final MongoTemplate mongo;
    private final ClassAliasRenames classAliases;

    public CollectionRenames(MongoTemplate mongo, ClassAliasRenames classAliases) {
        this.mongo = mongo;
        this.classAliases = classAliases;
    }

    private static Map<String, String> renames() {
        Map<String, String> m = new LinkedHashMap<>();
        for (String old : new String[] {
                "authority_changes", "campaign_recipients", "campaign_senders", "campaign_settings", "connect_codes",
                "contact_attributes", "contact_filters", "contact_labels", "contact_notes", "enablement_changes",
                "inbox_settings", "message_templates", "phone_numbers", "revoked_tokens", "role_changes",
                "wa_number_lookups", "wa_web_chat_actions", "wa_web_chats", "wa_web_messages"})
            m.put(old, camel(old));
        for (String bucket : new String[] {"chat_media", "template_media", "campaign_csv"}) {
            m.put(bucket + ".files", camel(bucket) + ".files");
            m.put(bucket + ".chunks", camel(bucket) + ".chunks");
        }
        return Map.copyOf(m);
    }

    private static String camel(String snake) {
        StringBuilder out = new StringBuilder();
        boolean upper = false;
        for (char c : snake.toCharArray()) {
            if (c == '_') {
                upper = true;
            } else {
                out.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
            }
        }
        return out.toString();
    }

    @Override
    public void afterSingletonsInstantiated() {
        migrate();
        classAliases.migrate();
    }

    public void migrate() {
        RENAMES.forEach(this::move);
        if (mongo.collectionExists(LEGACY_SYSTEM_STATE) && count(LEGACY_SYSTEM_STATE) == 0) {
            mongo.dropCollection(LEGACY_SYSTEM_STATE);
            log.info("dropped the empty legacy collection {}", LEGACY_SYSTEM_STATE);
        }
    }

    private void move(String old, String current) {
        if (!mongo.collectionExists(old)) return;
        long documents = count(old);
        if (mongo.collectionExists(current) && count(current) > 0) {
            if (documents == 0) {
                mongo.dropCollection(old);
                log.info("dropped the empty old collection {}; {} is in use", old, current);
            } else {
                log.error("collections {} ({} documents) and {} both hold documents; left both as they are, {} is the "
                        + "one in use", old, documents, current, current);
            }
            return;
        }
        mongo.getCollection(old).renameCollection(new MongoNamespace(mongo.getDb().getName(), current),
                new RenameCollectionOptions().dropTarget(true));
        createIndexes(current);
        log.info("renamed collection {} to {} ({} documents)", old, current, documents);
    }

    /** The rename replaced the collection Spring Data had indexed at start-up, so create the code's indexes again. */
    private void createIndexes(String collection) {
        var context = mongo.getConverter().getMappingContext();
        IndexResolver resolver = IndexResolver.create(context);
        for (MongoPersistentEntity<?> entity : context.getPersistentEntities()) {
            if (!entity.getCollection().equals(collection)) continue;
            for (IndexDefinition index : resolver.resolveIndexFor(entity.getTypeInformation())) {
                try {
                    mongo.indexOps(collection).createIndex(index);
                } catch (RuntimeException e) {
                    log.warn("could not create index {} on {}: {}", index.getIndexOptions(), collection, e.toString());
                }
            }
        }
    }

    private long count(String collection) {
        return mongo.getCollection(collection).countDocuments();
    }
}
