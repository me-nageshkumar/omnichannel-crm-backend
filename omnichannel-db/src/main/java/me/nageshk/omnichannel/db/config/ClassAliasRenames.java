package me.nageshk.omnichannel.db.config;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;

/**
 * Rewrites the {@code _class} type hints stored before the code moved into modules, from the old package
 * ({@code me.nageshk.omnichannel.contact.advanced.Rule}) to the current one
 * ({@code me.nageshk.omnichannel.db.contact.advanced.Rule}), in fields whose values are typed only by their hint.
 *
 * <p>Only such polymorphic fields are rewritten: an advanced filter's rule tree no longer loads until its members carry
 * current hints. Every other stale hint, on a document or a concrete nested value, is left as stored, since Spring Data
 * falls back to the declared type; rewriting them would mean scanning every collection on every start. Run by
 * {@link CollectionRenames} after the collection renames, before the web server, the schedulers and the seeders start.
 * Hints already current, or naming no class of this application, are left alone.
 */
@Slf4j
@Component
public class ClassAliasRenames {

    private static final String BASE = "me.nageshk.omnichannel.";
    /** Every persistent class lives in the db module, so its current name is its old one with this segment added. */
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

    /** The current name for an old hint, when the hint is old and the class exists under the db module. */
    private Optional<String> currentName(String hint) {
        if (!hint.startsWith(BASE) || hint.startsWith(BASE + MODULE)) return Optional.empty();
        String current = BASE + MODULE + hint.substring(BASE.length());
        return ClassUtils.isPresent(current, getClass().getClassLoader()) ? Optional.of(current) : Optional.empty();
    }
}
