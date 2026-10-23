package me.nageshk.omnichannel.db.config;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.mapping.MongoPersistentEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;

/**
 * Rewrites the {@code _class} type hints stored before the code moved into modules, from the old package
 * ({@code me.nageshk.omnichannel.contact.Contact}) to the current one ({@code me.nageshk.omnichannel.db.contact.Contact}).
 *
 * <p>A stale hint on a document is harmless (Spring Data falls back to the declared type), but the members of an
 * advanced filter's rule tree are typed only by their hint, so such a filter no longer loads until its tree is
 * rewritten. Run by {@link CollectionRenames} after the collection renames, before the web server, the schedulers and
 * the seeders start. Hints already current, or naming no class of this application, are left alone.
 */
@Slf4j
@Component
public class ClassAliasRenames {

    private static final String BASE = "me.nageshk.omnichannel.";
    /** Every persistent class lives in the db module, so its old name is its current one without this segment. */
    private static final String MODULE = "db.";
    /** Collection → field holding values whose subtype is known only from their hint. */
    private static final Map<String, String> POLYMORPHIC = Map.of("contactFilters", "rules");

    private final MongoTemplate mongo;

    public ClassAliasRenames(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    /** Returns how many documents changed. */
    public long migrate() {
        long changed = 0;
        for (MongoPersistentEntity<?> entity : mongo.getConverter().getMappingContext().getPersistentEntities()) {
            if (!entity.isAnnotationPresent(org.springframework.data.mongodb.core.mapping.Document.class)) continue;
            String current = entity.getType().getName();
            String old = oldName(current);
            if (old == null) continue;
            long n = mongo.getCollection(entity.getCollection())
                    .updateMany(new Document("_class", old), new Document("$set", new Document("_class", current)))
                    .getModifiedCount();
            if (n > 0) log.info("updated the type hint of {} documents in {} to {}", n, entity.getCollection(), current);
            changed += n;
        }
        for (var e : POLYMORPHIC.entrySet()) {
            changed += rewriteNested(e.getKey(), e.getValue());
        }
        return changed;
    }

    private long rewriteNested(String collection, String field) {
        long changed = 0;
        for (Document doc : mongo.getCollection(collection).find(new Document(field, new Document("$exists", true)))) {
            Object value = doc.get(field);
            if (rewrite(value)) {
                mongo.getCollection(collection).updateOne(new Document("_id", doc.get("_id")),
                        new Document("$set", new Document(field, value)));
                changed++;
            }
        }
        if (changed > 0) log.info("updated the nested type hints of {} documents in {}.{}", changed, collection, field);
        return changed;
    }

    /** Rewrites stale hints inside {@code value} in place; true when anything changed. */
    private boolean rewrite(Object value) {
        boolean changed = false;
        if (value instanceof Document d) {
            if (d.get("_class") instanceof String hint) {
                Optional<String> current = currentName(hint);
                if (current.isPresent()) {
                    d.put("_class", current.get());
                    changed = true;
                }
            }
            for (Object child : d.values()) changed |= rewrite(child);
        } else if (value instanceof List<?> list) {
            for (Object child : list) changed |= rewrite(child);
        }
        return changed;
    }

    /** The old name of a class in the db module, or null for a class outside it. */
    private static String oldName(String current) {
        return current.startsWith(BASE + MODULE) ? BASE + current.substring((BASE + MODULE).length()) : null;
    }

    /** The current name for an old hint, when the hint is old and the class exists under the db module. */
    private Optional<String> currentName(String hint) {
        if (!hint.startsWith(BASE) || hint.startsWith(BASE + MODULE)) return Optional.empty();
        String current = BASE + MODULE + hint.substring(BASE.length());
        return ClassUtils.isPresent(current, getClass().getClassLoader()) ? Optional.of(current) : Optional.empty();
    }
}
