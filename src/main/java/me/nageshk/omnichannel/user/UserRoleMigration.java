package me.nageshk.omnichannel.user;

import com.mongodb.client.MongoCollection;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import me.nageshk.omnichannel.access.BuiltInRoles;
import me.nageshk.omnichannel.access.RoleRepository;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

/**
 * One-off upgrade of users written by the old enum model: {@code role} becomes {@code roleId},
 * {@code workspaceRoles} is added, usernames are lower-cased. Works on raw documents because the enum
 * no longer exists. Idempotent; runs in every profile after the roles are seeded.
 */
@Component
@Order(2)
public class UserRoleMigration implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(UserRoleMigration.class);

    /** Legacy enum value to the built-in role that replaces it. */
    private static final Map<String, String> LEGACY_ROLES = Map.of(
            "SUPER_ADMIN", BuiltInRoles.SUPER_ADMIN,
            "USER", BuiltInRoles.WHATSAPP_DEVICE);

    private final MongoTemplate mongo;
    private final RoleRepository roles;

    public UserRoleMigration(MongoTemplate mongo, RoleRepository roles) {
        this.mongo = mongo;
        this.roles = roles;
    }

    @Override
    public void run(ApplicationArguments args) {
        migrate();
    }

    public void migrate() {
        MongoCollection<Document> users = mongo.getCollection("users");
        List<Document> docs = users.find().into(new ArrayList<>());

        // Resolve every change first so an unknown role aborts before anything is written.
        List<Document> updates = new ArrayList<>();
        for (Document doc : docs) {
            Document set = new Document();
            Document unset = new Document();
            Object legacy = doc.get("role");
            if (legacy != null) {
                set.append("roleId", roleIdFor(legacy.toString(), doc.getString("username")));
                unset.append("role", "");
            }
            if (doc.get("workspaceRoles") == null) set.append("workspaceRoles", List.of());
            String username = doc.getString("username");
            if (username != null && !username.equals(username.toLowerCase(Locale.ROOT))) {
                set.append("username", username.toLowerCase(Locale.ROOT));
            }
            if (set.isEmpty() && unset.isEmpty()) continue;
            Document update = new Document();
            if (!set.isEmpty()) update.append("$set", set);
            if (!unset.isEmpty()) update.append("$unset", unset);
            updates.add(new Document("_id", doc.get("_id")).append("update", update));
        }
        for (Document u : updates) {
            users.updateOne(new Document("_id", u.get("_id")), u.get("update", Document.class));
        }
        if (!updates.isEmpty()) log.info("migrated {} user document(s) to role ids", updates.size());
    }

    private String roleIdFor(String legacy, String username) {
        String roleName = LEGACY_ROLES.get(legacy);
        if (roleName == null) {
            throw new IllegalStateException("user '" + username + "' has legacy role " + legacy
                    + " which has no equivalent; fix or remove the user before starting");
        }
        return roles.findByName(roleName)
                .orElseThrow(() -> new IllegalStateException("built-in role " + roleName + " is missing"))
                .getId();
    }
}
