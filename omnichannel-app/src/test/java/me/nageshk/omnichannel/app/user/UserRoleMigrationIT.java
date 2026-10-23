package me.nageshk.omnichannel.app.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import me.nageshk.omnichannel.app.support.TestUsers;
import me.nageshk.omnichannel.db.user.User;
import me.nageshk.omnichannel.db.user.UserRepository;
import me.nageshk.omnichannel.security.access.BuiltInRoles;
import me.nageshk.omnichannel.security.user.UserRoleMigration;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

@SpringBootTest
class UserRoleMigrationIT {

    @Autowired UserRoleMigration migration;
    @Autowired TestUsers testUsers;
    @Autowired MongoTemplate mongo;
    @Autowired UserRepository repo;

    @BeforeEach
    void clean() {
        testUsers.deleteAll();
    }

    private void insert(String username, String role) {
        mongo.getCollection("users").insertOne(new Document("username", username).append("name", username)
                .append("password", "x").append("role", role).append("active", true));
    }

    private Document raw(String username) {
        return mongo.getCollection("users").find(new Document("username", username)).first();
    }

    @Test
    void mapsLegacyRolesToRoleIds() {
        insert("root", "SUPER_ADMIN");
        migration.migrate();

        Document root = raw("root");
        assertThat(root.getString("roleId")).isEqualTo(testUsers.roleId(BuiltInRoles.SUPER_ADMIN));
        assertThat(root.containsKey("role")).isFalse();
        assertThat(root.getList("workspaceRoles", Object.class)).isEmpty();
    }

    @Test
    void usernamesAreLowerCased() {
        insert("NageshK", "SUPER_ADMIN");
        migration.migrate();

        assertThat(raw("nageshk")).isNotNull();
        assertThat(raw("NageshK")).isNull();
    }

    @Test
    void unknownRoleFailsWithClearMessage() {
        insert("boss", "APP_ADMIN");

        assertThatThrownBy(() -> migration.migrate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("APP_ADMIN");
    }

    @Test
    void migrateIsIdempotent() {
        insert("root", "SUPER_ADMIN");
        migration.migrate();
        Document first = raw("root");
        migration.migrate();

        assertThat(raw("root")).isEqualTo(first);
        assertThat(mongo.count(new Query(Criteria.where("role").exists(true)), "users")).isZero();
    }

    @Test
    void legacyDocumentWithoutVersionGetsVersionZeroAndCanBeSaved() {
        insert("root", "SUPER_ADMIN");
        migration.migrate();

        assertThat(raw("root").get("version")).isEqualTo(0L);
        User root = repo.findByUsername("root").orElseThrow();
        root.setActive(false);
        repo.save(root);

        assertThat(mongo.count(new Query(), "users")).isEqualTo(1);
        assertThat(raw("root").getBoolean("active")).isFalse();
        assertThat(raw("root").get("version")).isEqualTo(1L);
    }

    @Test
    void existingVersionIsLeftAlone() {
        mongo.getCollection("users").insertOne(new Document("username", "kept").append("name", "kept")
                .append("password", "x").append("workspaceRoles", java.util.List.of()).append("active", true)
                .append("version", 7L));
        migration.migrate();

        assertThat(raw("kept").get("version")).isEqualTo(7L);
    }
}
