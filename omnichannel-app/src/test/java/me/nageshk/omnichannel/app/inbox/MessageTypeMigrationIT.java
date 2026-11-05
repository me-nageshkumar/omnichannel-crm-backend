package me.nageshk.omnichannel.app.inbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import me.nageshk.omnichannel.db.config.MessageTypeMigration;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;

@SpringBootTest
class MessageTypeMigrationIT {

    private static final String MESSAGES = "waWebMessages";
    private static final String CHATS = "waWebChats";

    /** Old stored type → the type after the migration; the last three are not renamed. */
    private static final Map<String, String> TYPES = types();

    @Autowired MongoTemplate mongo;
    @Autowired MessageTypeMigration migration;

    private static Map<String, String> types() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("chat", "text");
        m.put("ptt", "audio");
        m.put("vcard", "contacts");
        m.put("multi_vcard", "contacts");
        m.put("unknown", "unsupported");
        for (String t : new String[] {"list", "buttons", "hsm", "template", "native_flow", "list_response"})
            m.put(t, "interactive");
        m.put("buttons_response", "button");
        m.put("template_button_reply", "button");
        m.put("image", "image");
        m.put("interactive", "interactive");
        m.put("poll_creation", "poll_creation");
        return m;
    }

    @BeforeEach
    @AfterEach
    void clean() {
        mongo.getCollection(MESSAGES).deleteMany(new Document());
        mongo.getCollection(CHATS).deleteMany(new Document());
    }

    private void seed() {
        TYPES.keySet().forEach(t -> mongo.getCollection(MESSAGES).insertOne(new Document("_id", "m-" + t)
                .append("phoneNumberId", "pn-1").append("waMessageId", "w-" + t).append("type", t)
                .append("body", "kept " + t)));
        mongo.getCollection(CHATS).insertOne(chat("c-ptt").append("lastMessage",
                new Document("direction", "IN").append("type", "ptt").append("text", "🎤 Voice message")));
        mongo.getCollection(CHATS).insertOne(chat("c-image").append("lastMessage",
                new Document("direction", "IN").append("type", "image").append("text", "📷 Photo")));
        mongo.getCollection(CHATS).insertOne(chat("c-none"));
    }

    private static Document chat(String id) {
        return new Document("_id", id).append("phoneNumberId", "pn-1").append("remoteId", id + "@c.us");
    }

    private Document message(String oldType) {
        return mongo.getCollection(MESSAGES).find(new Document("_id", "m-" + oldType)).first();
    }

    private Document lastMessage(String chatId) {
        return mongo.getCollection(CHATS).find(new Document("_id", chatId)).first().get("lastMessage", Document.class);
    }

    @Test
    void renamesTheOldTypesOnMessagesAndChatPreviews() {
        seed();

        MessageTypeMigration.Result result = migration.migrate();

        TYPES.forEach((old, now) -> {
            assertThat(message(old).getString("type")).as(old).isEqualTo(now);
            assertThat(message(old).getString("body")).isEqualTo("kept " + old);
        });
        assertThat(result.messages()).isEqualTo(TYPES.size() - 3);
        assertThat(lastMessage("c-ptt").getString("type")).isEqualTo("audio");
        assertThat(lastMessage("c-ptt").getString("text")).isEqualTo("🎤 Voice message");
        assertThat(lastMessage("c-image").getString("type")).isEqualTo("image");
        assertThat(mongo.getCollection(CHATS).find(new Document("_id", "c-none")).first())
                .doesNotContainKey("lastMessage");
        assertThat(result.chats()).isEqualTo(1);
    }

    @Test
    void runningTwiceChangesNothing() {
        seed();
        migration.migrate();

        MessageTypeMigration.Result second = migration.migrate();

        assertThat(second.messages()).isZero();
        assertThat(second.chats()).isZero();
        assertThat(message("chat").getString("type")).isEqualTo("text");
    }
}
