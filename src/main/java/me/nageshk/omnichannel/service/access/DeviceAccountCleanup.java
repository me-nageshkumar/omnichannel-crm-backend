package me.nageshk.omnichannel.service.access;

import com.mongodb.client.MongoCollection;
import me.nageshk.omnichannel.security.access.AuthorityCatalog;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

/**
 * Removes the shared extension login that device keys replaced: users of the {@code WHATSAPP_DEVICE}
 * role (and legacy {@code role: USER} users), the role itself, and the {@code whatsapp:bridge}
 * authority wherever a role still holds it. Runs before {@link me.nageshk.omnichannel.security.user.UserRoleMigration},
 * in every profile; idempotent.
 */
@Component
@Order(2)
public class DeviceAccountCleanup implements ApplicationRunner {

    static final String DEVICE_ROLE = "WHATSAPP_DEVICE";
    static final String BRIDGE_AUTHORITY = "whatsapp:bridge";

    private static final Logger log = LoggerFactory.getLogger(DeviceAccountCleanup.class);

    private final MongoTemplate mongo;
    private final AuthorityCatalog catalog;

    public DeviceAccountCleanup(MongoTemplate mongo, AuthorityCatalog catalog) {
        this.mongo = mongo;
        this.catalog = catalog;
    }

    @Override
    public void run(ApplicationArguments args) {
        MongoCollection<Document> roles = mongo.getCollection("roles");
        MongoCollection<Document> users = mongo.getCollection("users");
        long removedUsers = users.deleteMany(new Document("role", "USER")).getDeletedCount();
        Document role = roles.find(new Document("name", DEVICE_ROLE)).first();
        if (role != null) {
            Object id = role.get("_id");
            String hex = id instanceof org.bson.types.ObjectId oid ? oid.toHexString() : String.valueOf(id);
            removedUsers += users.deleteMany(new Document("roleId", hex)).getDeletedCount();
            roles.deleteOne(new Document("_id", id));
        }
        long rolesEdited = roles.updateMany(new Document("authorities", BRIDGE_AUTHORITY),
                new Document("$pull", new Document("authorities", BRIDGE_AUTHORITY))).getModifiedCount();
        long authorities = mongo.getCollection("authorities")
                .deleteOne(new Document("_id", BRIDGE_AUTHORITY)).getDeletedCount();
        if (role != null || removedUsers + rolesEdited + authorities > 0) {
            log.info("removed the shared device login: {} user(s), role {}, {} role grant(s), {} authority",
                    removedUsers, role != null ? DEVICE_ROLE : "-", rolesEdited, authorities);
            catalog.evictAll();
        }
    }
}
