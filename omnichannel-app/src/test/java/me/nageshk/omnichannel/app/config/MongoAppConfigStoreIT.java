package me.nageshk.omnichannel.app.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.mongodb.client.MongoClient;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;

@SpringBootTest
class MongoAppConfigStoreIT {

    @Autowired MongoClient client;
    @Autowired MongoTemplate mongo;

    @BeforeEach
    void clean() {
        mongo.dropCollection(MongoAppConfigStore.COLLECTION);
        mongo.dropCollection("aiConnections");
        mongo.dropCollection("aiProfiles");
    }

    @Test
    void sealedSecretsAreFound() {
        MongoAppConfigStore store = store();
        assertThat(store.sealedSecretsExist()).isFalse();
        mongo.getCollection("aiProfiles").insertOne(new org.bson.Document("name", "P").append("mcpServers",
                List.of(new org.bson.Document("label", "s").append("secret", new org.bson.Document("keyId", "k1").append("ciphertext", "x")))));
        assertThat(store.sealedSecretsExist()).isTrue();
        mongo.dropCollection("aiProfiles");
        mongo.getCollection("aiConnections").insertOne(new org.bson.Document("name", "C").append("apiKey",
                new org.bson.Document("keyId", "k1").append("ciphertext", "x")));
        assertThat(store.sealedSecretsExist()).isTrue();
    }

    private MongoAppConfigStore store() {
        return new MongoAppConfigStore(client, mongo.getDb().getName(), false);
    }

    @Test
    void saveIfAbsentWritesOnce() {
        MongoAppConfigStore store = store();
        store.saveIfAbsent("secretsKeys", List.of("k1:first"));
        store.saveIfAbsent("secretsKeys", List.of("k1:second"));
        store.saveIfAbsent("pagesBaseUrl", "https://a.example");
        assertThat(store.read()).containsEntry("secretsKeys", List.of("k1:first")).containsEntry("pagesBaseUrl",
                "https://a.example");
    }

    @Test
    void anEmptyListOrBlankCountsAsAbsent() {
        MongoAppConfigStore store = store();
        store.save("secretsKeys", List.of());
        store.save("pagesBaseUrl", "  ");
        store.saveIfAbsent("secretsKeys", List.of("k1:filled"));
        store.saveIfAbsent("pagesBaseUrl", "https://d.example");
        assertThat(store.read()).containsEntry("secretsKeys", List.of("k1:filled")).containsEntry("pagesBaseUrl",
                "https://d.example");
    }

    @Test
    void readReturnsTheDocument() {
        MongoAppConfigStore store = store();
        assertThat(store.read()).isEmpty();
        store.save("pagesBaseUrl", "https://b.example");
        store.save("pagesBaseUrl", "https://c.example");
        assertThat(store.read()).containsEntry("pagesBaseUrl", "https://c.example");
        assertThat(mongo.getCollection(MongoAppConfigStore.COLLECTION).countDocuments()).isEqualTo(1);
    }
}
