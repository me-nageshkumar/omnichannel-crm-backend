package me.nageshk.omnichannel.db.config;

import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

/**
 * Moves the stored message types from WhatsApp Web's names to the Cloud API webhook's: {@code chat} → {@code text},
 * {@code ptt} → {@code audio}, a contact card → {@code contacts}, {@code unknown} → {@code unsupported}, a business's
 * menu or template and a list reply → {@code interactive}, a button reply → {@code button}. On the messages'
 * {@code type} and the chats' preview {@code lastMessage.type}; nothing else on the row changes.
 *
 * <p>Run from {@link CollectionRenames#afterSingletonsInstantiated()}, after {@link ContactIdsMigration}. Idempotent:
 * no old name is a new name ({@code interactive} is kept as it is for that reason), so a second run matches nothing.
 */
@Slf4j
@Component
public class MessageTypeMigration {

    static final String MESSAGES = "waWebMessages";
    static final String CHATS = "waWebChats";

    /** New name → the old names it replaces. */
    static final Map<String, List<String>> RENAMES = renames();

    /** How many documents each collection had renamed. */
    public record Result(long messages, long chats) {
    }

    private final MongoTemplate mongo;

    public MessageTypeMigration(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    private static Map<String, List<String>> renames() {
        Map<String, List<String>> m = new LinkedHashMap<>();
        m.put("text", List.of("chat"));
        m.put("audio", List.of("ptt"));
        m.put("contacts", List.of("vcard", "multi_vcard"));
        m.put("unsupported", List.of("unknown"));
        m.put("interactive", List.of("list", "buttons", "hsm", "template", "native_flow", "list_response"));
        m.put("button", List.of("buttons_response", "template_button_reply"));
        return m;
    }

    public Result migrate() {
        return new Result(rename(MESSAGES, "type"), rename(CHATS, "lastMessage.type"));
    }

    private long rename(String collection, String field) {
        if (!mongo.collectionExists(collection)) return 0;
        long changed = 0;
        for (Map.Entry<String, List<String>> e : RENAMES.entrySet()) {
            changed += mongo.getCollection(collection)
                    .updateMany(Filters.in(field, e.getValue()), Updates.set(field, e.getKey())).getModifiedCount();
        }
        if (changed > 0) log.info("{}: moved {} to Meta's type names on {} documents", collection, field, changed);
        return changed;
    }
}
