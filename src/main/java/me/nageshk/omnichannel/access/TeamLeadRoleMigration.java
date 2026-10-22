package me.nageshk.omnichannel.access;

import static org.springframework.data.mongodb.core.query.Criteria.where;

import com.mongodb.client.model.Filters;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import com.mongodb.client.result.UpdateResult;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/**
 * One-off: the TEAM_LEAD role was removed (chat-assignment design §0). Everyone who held it, as their company role
 * or in a workspace, becomes an AGENT; the role document is deleted and the deletion recorded. Runs after
 * {@link RoleSeeder} (which no longer seeds TEAM_LEAD) and is a no-op once the role is gone.
 */
@Component
@Order(2)
public class TeamLeadRoleMigration implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(TeamLeadRoleMigration.class);
    static final String REMOVED_ROLE = "TEAM_LEAD";

    private final MongoTemplate mongo;
    private final RoleRepository roles;
    private final RoleChangeRepository changes;
    private final Clock clock;

    public TeamLeadRoleMigration(MongoTemplate mongo, RoleRepository roles, RoleChangeRepository changes,
            Clock clock) {
        this.mongo = mongo;
        this.roles = roles;
        this.changes = changes;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        migrate();
    }

    public void migrate() {
        Role removed = roles.findByName(REMOVED_ROLE).orElse(null);
        if (removed == null) return;
        String agentId = roles.findByName(BuiltInRoles.AGENT)
                .orElseThrow(() -> new IllegalStateException("built-in role AGENT is missing")).getId();

        UpdateResult company = mongo.updateMulti(new Query(where("roleId").is(removed.getId())),
                new Update().set("roleId", agentId), "users");
        UpdateResult brand = mongo.getCollection("users").updateMany(
                Filters.eq("workspaceRoles.roleId", removed.getId()),
                Updates.set("workspaceRoles.$[r].roleId", agentId),
                new UpdateOptions().arrayFilters(List.of(new Document("r.roleId", removed.getId()))));

        RoleChange change = new RoleChange();
        change.setRoleId(removed.getId());
        change.setRoleName(removed.getName());
        change.setChangedBy("system");
        change.setAt(Instant.now(clock));
        change.setAction(RoleChange.Action.DELETED);
        change.setRemoved(List.copyOf(removed.getAuthorities()));
        change.setDescriptionBefore(removed.getDescription());
        changes.save(change);
        roles.delete(removed);
        log.info("removed role TEAM_LEAD: {} company-role user(s) and {} workspace-role user(s) are now AGENT",
                company.getModifiedCount(), brand.getModifiedCount());
    }
}
