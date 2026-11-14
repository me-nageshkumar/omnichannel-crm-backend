package me.nageshk.omnichannel.app.chatbot;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import me.nageshk.omnichannel.db.chatbot.ChatbotSettings;
import me.nageshk.omnichannel.db.config.ChatbotSettingsMigration;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import tools.jackson.databind.JsonNode;

/** Spec 2026-11-14 §5: stored full settings become a chatbot layer once; defaults turn into inherited fields. */
class ChatbotSettingsMigrationIT extends ChatbotTestBase {

    @Autowired ChatbotSettingsMigration migration;

    @BeforeEach
    void forgetTheMarker() {
        mongo.dropCollection(ChatbotSettingsMigration.MARKERS);
    }

    private static Map<String, Object> oldDefaults() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("reminderAfterMinutes", null);
        s.put("reminderMessage", null);
        s.put("endAfterMinutes", 120);
        s.put("endMessage", null);
        s.put("invalidReplyRetries", 2);
        s.put("retryMessage", ChatbotSettings.DEFAULT_RETRY_MESSAGE);
        s.put("giveUpMessage", null);
        s.put("stopOnAgentReply", true);
        return s;
    }

    private String insertOld(Map<String, Object> settings) {
        ObjectId id = new ObjectId();
        mongo.getCollection("chatbots").insertOne(new Document("_id", id).append("customerId", acme.getId())
                .append("workspaceId", brand.getId()).append("name", "Old " + id).append("status", "DRAFT")
                .append("flow", new Document("nodes", List.of()).append("edges", List.of()))
                .append("versionNumber", 0).append("version", 0L).append("settings", new Document(settings)));
        return id.toHexString();
    }

    private Document stored(String id) {
        return mongo.getCollection("chatbots").find(new Document("_id", new ObjectId(id))).first()
                .get("settings", Document.class);
    }

    @Test
    void defaultsBecomeInherited() {
        String id = insertOld(oldDefaults());

        assertThat(migration.migrate()).isEqualTo(1);

        assertThat(stored(id)).isEmpty();
        JsonNode view = bots(HttpMethod.GET, "/" + id, manager, null).getBody();
        view.get("inheritedSources").properties().forEach(e -> assertThat(e.getValue().asString()).isEqualTo("PLATFORM"));
        view.get("settings").properties().forEach(e -> assertThat(e.getValue().isNull()).as(e.getKey()).isTrue());
    }

    @Test
    void customValuesStayOnTheChatbot() {
        Map<String, Object> s = oldDefaults();
        s.put("endAfterMinutes", 45);
        s.put("retryMessage", "Custom");
        s.put("endMessage", "Bye");
        String id = insertOld(s);

        migration.migrate();

        assertThat(stored(id)).containsOnlyKeys("endAfterMinutes", "retryMessage", "endMessage");
        assertThat(stored(id).getInteger("endAfterMinutes")).isEqualTo(45);
        assertThat(stored(id).getString("retryMessage")).isEqualTo("Custom");
    }

    @Test
    void reminderIsSplit() {
        Map<String, Object> s = oldDefaults();
        s.put("reminderAfterMinutes", 20);
        s.put("reminderMessage", "Ping");
        String id = insertOld(s);

        migration.migrate();

        assertThat(stored(id)).containsOnlyKeys("reminderOn", "reminderAfterMinutes", "reminderMessage");
        assertThat(stored(id).getBoolean("reminderOn")).isTrue();
        assertThat(stored(id).getInteger("reminderAfterMinutes")).isEqualTo(20);
        assertThat(stored(id).getString("reminderMessage")).isEqualTo("Ping");
    }

    @Test
    void runsOnlyOnce() {
        String id = insertOld(oldDefaults());
        migration.migrate();
        mongo.getCollection("chatbots").updateOne(new Document("_id", new ObjectId(id)),
                new Document("$set", new Document("settings.endAfterMinutes", 120)));

        assertThat(migration.migrate()).isZero();

        assertThat(stored(id).getInteger("endAfterMinutes")).isEqualTo(120);
    }

    @Test
    void noChatbotsCollectionIsFine() {
        mongo.dropCollection("chatbots");

        assertThat(migration.migrate()).isZero();

        assertThat(mongo.getCollection(ChatbotSettingsMigration.MARKERS).countDocuments()).isEqualTo(1);
    }

    // review fix: an editor tab opened before the deploy still holds the old full settings and the old version
    @Test
    void migrationBumpsTheVersionSoAStaleEditorGets409() {
        String id = insertOld(oldDefaults());
        migration.migrate();

        Map<String, Object> save = new LinkedHashMap<>();
        save.put("flow", Map.of("nodes", List.of(), "edges", List.of()));
        save.put("settings", oldDefaults());
        save.put("version", 0L);

        assertThat(bots(HttpMethod.PUT, "/" + id, manager, save).getStatusCode())
                .isEqualTo(org.springframework.http.HttpStatus.CONFLICT);
        assertThat(stored(id)).isEmpty();
    }
}
