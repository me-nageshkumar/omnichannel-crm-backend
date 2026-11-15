package me.nageshk.omnichannel.db.config;

import com.mongodb.client.MongoCollection;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import me.nageshk.omnichannel.db.chatbot.ChatbotSettings;
import org.bson.Document;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

/**
 * Turns each chatbot's stored full settings into its own layer (spec 2026-11-14 §5): a value equal to the old code
 * default becomes inherited, any other value stays set on the chatbot, and an old reminder ({@code
 * reminderAfterMinutes} set) gains {@code reminderOn: true}.
 *
 * <p>Runs once: a marker {@code {_id: "chatbotSettingsLayers"}} in {@link #MARKERS} is written after the pass, because
 * after this change a chatbot may set a field to a default value on purpose. Run from
 * {@link CollectionRenames#afterSingletonsInstantiated()}, last.
 */
@Slf4j
@Component
public class ChatbotSettingsMigration {

    public static final String MARKERS = "dataMigrations";
    static final String MARKER_ID = "chatbotSettingsLayers";
    static final String CHATBOTS = "chatbots";

    /** The old code defaults, dropped so the field inherits. */
    private static final Map<String, Object> OLD_DEFAULTS = Map.of("endAfterMinutes", 120, "invalidReplyRetries", 2,
            "retryMessage", ChatbotSettings.DEFAULT_RETRY_MESSAGE, "stopOnAgentReply", true);

    private final MongoTemplate mongo;

    public ChatbotSettingsMigration(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    /** How many chatbots were changed; 0 when the pass already ran. */
    public long migrate() {
        MongoCollection<Document> markers = mongo.getCollection(MARKERS);
        if (markers.find(new Document("_id", MARKER_ID)).first() != null) return 0;
        long changed = 0;
        if (mongo.collectionExists(CHATBOTS)) {
            MongoCollection<Document> chatbots = mongo.getCollection(CHATBOTS);
            for (Document c : chatbots.find(new Document("settings", new Document("$type", "object")))) {
                Document old = c.get("settings", Document.class);
                Document layer = layer(old);
                if (!layer.equals(old)) {
                    // bump the version too, so an editor opened before the change gets a 409 instead of saving the old shape back
                    chatbots.updateOne(new Document("_id", c.get("_id")), new Document("$set", new Document("settings", layer))
                            .append("$inc", new Document("version", 1L)));
                    changed++;
                }
            }
        }
        try {
            markers.insertOne(new Document("_id", MARKER_ID).append("appliedAt", Instant.now()));
        } catch (com.mongodb.MongoWriteException | DuplicateKeyException raced) {
            log.info("chatbot settings migration marker already written by another instance");
        }
        if (changed > 0) log.info("chatbots: moved {} stored settings to layers", changed);
        return changed;
    }

    static Document layer(Document old) {
        Document out = new Document();
        for (Map.Entry<String, Object> e : old.entrySet()) {
            String k = e.getKey();
            Object v = e.getValue();
            if (v == null) continue;
            if (OLD_DEFAULTS.containsKey(k) && Objects.equals(OLD_DEFAULTS.get(k), v)) continue;
            out.put(k, v);
        }
        if (out.get("reminderAfterMinutes") != null && !out.containsKey("reminderOn")) {
            out.put("reminderOn", true);
        } else if (out.get("reminderAfterMinutes") == null) {
            out.remove("reminderMessage");
        }
        return out;
    }
}
