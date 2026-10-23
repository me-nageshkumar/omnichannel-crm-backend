package me.nageshk.omnichannel.app.access;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import me.nageshk.omnichannel.app.support.TestUsers;
import me.nageshk.omnichannel.security.access.AuthorityCatalog;
import me.nageshk.omnichannel.service.access.DeviceAccountCleanup;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;

@SpringBootTest
class DeviceAccountCleanupIT {

    @Autowired DeviceAccountCleanup cleanup;
    @Autowired MongoTemplate mongo;
    @Autowired TestUsers testUsers;
    @Autowired AuthorityCatalog catalog;

    String deviceRoleId;

    @BeforeEach
    void seedLegacy() {
        testUsers.deleteAll();
        mongo.getCollection("roles").insertOne(new Document("name", "WHATSAPP_DEVICE").append("level", "PLATFORM")
                .append("builtIn", true).append("authorities", List.of("whatsapp:bridge")));
        deviceRoleId = mongo.getCollection("roles").find(new Document("name", "WHATSAPP_DEVICE")).first()
                .getObjectId("_id").toHexString();
        mongo.getCollection("authorities").insertOne(new Document("_id", "whatsapp:bridge").append("group", "whatsapp")
                .append("level", "PLATFORM").append("description", "bridge").append("builtIn", true));
        mongo.getCollection("roles").updateOne(new Document("name", "ACCOUNT_MANAGER"),
                new Document("$push", new Document("authorities", "whatsapp:bridge")));
        mongo.getCollection("users").insertOne(new Document("username", "wabridge").append("roleId", deviceRoleId));
        mongo.getCollection("users").insertOne(new Document("username", "legacy").append("role", "USER"));
        mongo.getCollection("users").insertOne(new Document("username", "keep").append("roleId", "other"));
    }

    @AfterEach
    void clean() {
        testUsers.deleteAll();
    }

    private long users(String username) {
        return mongo.getCollection("users").countDocuments(new Document("username", username));
    }

    @Test
    void removesDeviceUserRoleAndAuthority() {
        cleanup.run(null);

        assertThat(users("wabridge")).isZero();
        assertThat(users("legacy")).isZero();
        assertThat(mongo.getCollection("roles").countDocuments(new Document("name", "WHATSAPP_DEVICE"))).isZero();
        assertThat(mongo.getCollection("authorities").countDocuments(new Document("_id", "whatsapp:bridge"))).isZero();
        assertThat(catalog.byCode("whatsapp:bridge")).isEmpty();
    }

    @Test
    void keepsOtherRolesAuthoritiesIntact() {
        cleanup.run(null);

        List<String> am = mongo.getCollection("roles").find(new Document("name", "ACCOUNT_MANAGER")).first()
                .getList("authorities", String.class);
        assertThat(am).doesNotContain("whatsapp:bridge").contains("customer:view", "customer:create");
        assertThat(users("keep")).isEqualTo(1);
    }

    @Test
    void isIdempotent() {
        cleanup.run(null);
        cleanup.run(null);
        assertThat(users("keep")).isEqualTo(1);
    }
}
