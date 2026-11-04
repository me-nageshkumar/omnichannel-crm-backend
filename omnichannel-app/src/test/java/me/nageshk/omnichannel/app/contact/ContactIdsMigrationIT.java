package me.nageshk.omnichannel.app.contact;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import me.nageshk.omnichannel.app.support.TestUsers;
import me.nageshk.omnichannel.db.config.ContactIdsMigration;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexInfo;

@SpringBootTest
class ContactIdsMigrationIT {

    @Autowired MongoTemplate mongo;
    @Autowired ContactIdsMigration migration;
    @Autowired TestUsers testUsers;

    @BeforeEach
    void setUp() {
        testUsers.deleteAll();
    }

    @AfterEach
    void clean() {
        testUsers.deleteAll();
        if (indexNames().contains("ws_waId")) mongo.indexOps("contacts").dropIndex("ws_waId");
    }

    private List<String> indexNames() {
        return mongo.indexOps("contacts").getIndexInfo().stream().map(IndexInfo::getName).toList();
    }

    private Document stored(String id) {
        return mongo.getCollection("contacts").find(new Document("_id", id)).first();
    }

    @Test
    void oldWaIdBecomesWaIds() {
        mongo.getCollection("contacts").insertOne(new Document("_id", "c1").append("workspaceId", "w1")
                .append("waId", "111@lid").append("phone", "919812345678"));
        migration.migrate();
        Document d = stored("c1");
        assertThat(d.getList("waIds", String.class)).containsExactly("111@lid");
        assertThat(d).doesNotContainKey("waId");
        assertThat(d.getString("phone")).isEqualTo("919812345678");
    }

    @Test
    void migrationIsIdempotent() {
        mongo.getCollection("contacts").insertOne(new Document("_id", "c1").append("workspaceId", "w1")
                .append("waId", "111@lid"));
        mongo.getCollection("contacts").insertOne(new Document("_id", "c2").append("workspaceId", "w1")
                .append("waIds", List.of("222@lid", "919812345678@c.us")));
        migration.migrate();
        Document first = stored("c1");
        migration.migrate();
        assertThat(stored("c1")).isEqualTo(first);
        assertThat(stored("c2").getList("waIds", String.class)).containsExactly("222@lid", "919812345678@c.us");
        assertThat(stored("c2")).doesNotContainKey("waId");
    }

    @Test
    void oldUniqueIndexIsDropped() {
        mongo.indexOps("contacts").createIndex(new Index().named("ws_waId").on("workspaceId", org.springframework.data.domain.Sort.Direction.ASC)
                .on("waId", org.springframework.data.domain.Sort.Direction.ASC).unique());
        mongo.getCollection("contacts").insertOne(new Document("_id", "c1").append("workspaceId", "w1")
                .append("waId", "111@lid"));
        migration.migrate();
        assertThat(indexNames()).contains("ws_waIds").doesNotContain("ws_waId");
        assertThat(stored("c1").getList("waIds", String.class)).containsExactly("111@lid");
    }
}
