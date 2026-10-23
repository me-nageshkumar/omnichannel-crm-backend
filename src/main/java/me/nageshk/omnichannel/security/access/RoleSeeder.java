package me.nageshk.omnichannel.security.access;

import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.nageshk.omnichannel.db.access.AuthorityDefinition;
import me.nageshk.omnichannel.db.access.AuthorityDefinitionRepository;
import me.nageshk.omnichannel.db.access.Role;
import me.nageshk.omnichannel.db.access.RoleRepository;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

/**
 * Creates the built-in roles and the built-in authority documents, and hands newly introduced
 * authorities to their default roles. Never removes anything from a role: a removal made by the Super
 * Admin sticks because an authority is only handed out when its document does not exist yet, so the
 * existence of a document is what "seen" means. Also retires the legacy known-authorities record.
 * Runs in every profile.
 */
@Component
@Order(1)
public class RoleSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RoleSeeder.class);

    private static final Map<String, String> BUILT_INS = new LinkedHashMap<>();

    static {
        BUILT_INS.put(BuiltInRoles.SUPER_ADMIN, "Everything, always. Not editable.");
        BUILT_INS.put(BuiltInRoles.ACCOUNT_MANAGER, "Platform staff who manage customers");
        BUILT_INS.put(BuiltInRoles.CUSTOMER_ADMIN, "Runs a customer company and all its workspaces");
        BUILT_INS.put(BuiltInRoles.WORKSPACE_MANAGER, "Runs one workspace");
        BUILT_INS.put(BuiltInRoles.AGENT, "Handles conversations in a workspace");
    }

    private static final String LEGACY_STATE = "system_state";
    private static final String LEGACY_ID = "known-authorities";

    private final RoleRepository roles;
    private final AuthorityDefinitionRepository authorities;
    private final AuthorityCatalog catalog;
    private final MongoTemplate mongo;

    public RoleSeeder(RoleRepository roles, AuthorityDefinitionRepository authorities,
                      AuthorityCatalog catalog, MongoTemplate mongo) {
        this.roles = roles;
        this.authorities = authorities;
        this.catalog = catalog;
        this.mongo = mongo;
    }

    @Override
    public void run(ApplicationArguments args) {
        seed();
    }

    public void seed() {
        try {
            BUILT_INS.forEach((name, description) -> {
                if (roles.existsByName(name)) return;
                Role r = new Role();
                r.setName(name);
                r.setDescription(description);
                r.setLevel(BuiltInRoles.levelOf(name));
                r.setBuiltIn(true);
                roles.save(r);
                log.info("seeded built-in role {}", name);
            });

            Document legacy = mongo.findById(LEGACY_ID, Document.class, LEGACY_STATE);
            Set<String> seen = new HashSet<>();
            if (legacy != null && legacy.get("codes") instanceof List<?> codes) {
                codes.forEach(c -> seen.add(String.valueOf(c)));
            }
            for (Authority a : Authority.values()) {
                AuthorityDefinition existing = authorities.findById(a.code()).orElse(null);
                if (existing == null) {
                    // grant first, document last: the document is what marks the code "seen", so a crash
                    // in between is retried on the next start (adding to a Set is idempotent)
                    if (!seen.contains(a.code())) {
                        for (String roleName : a.defaultRoles()) {
                            roles.findByName(roleName).ifPresent(r -> {
                                r.getAuthorities().add(a.code());
                                roles.save(r);
                            });
                        }
                        log.info("added new authority {} to {}", a.code(), a.defaultRoles());
                    }
                    authorities.save(definitionOf(a));
                } else if (!existing.isBuiltIn() || existing.getLevel() != a.level()) {
                    existing.setBuiltIn(true);
                    existing.setLevel(a.level());
                    existing.setUpdatedAt(Instant.now());
                    existing.setUpdatedBy("system");
                    authorities.save(existing);
                    log.info("corrected built-in authority {}", a.code());
                }
            }
            if (legacy != null) {
                mongo.remove(new org.springframework.data.mongodb.core.query.Query(
                        org.springframework.data.mongodb.core.query.Criteria.where("_id").is(LEGACY_ID)), LEGACY_STATE);
            }
        } finally {
            catalog.evictAll();
        }
    }

    private static AuthorityDefinition definitionOf(Authority a) {
        AuthorityDefinition d = new AuthorityDefinition();
        d.setCode(a.code());
        d.setGroup(a.group());
        d.setLevel(a.level());
        d.setDescription(a.description());
        d.setBuiltIn(true);
        d.setCreatedAt(Instant.now());
        d.setCreatedBy("system");
        return d;
    }
}
