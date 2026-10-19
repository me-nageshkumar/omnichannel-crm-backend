package me.nageshk.omnichannel.access;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Creates the built-in roles and hands newly introduced authorities to their default roles. Never
 * removes anything: a removal made by the Super Admin sticks because already-seen codes are skipped.
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
        BUILT_INS.put(BuiltInRoles.WHATSAPP_DEVICE, "The WhatsApp bridge device account");
        BUILT_INS.put(BuiltInRoles.CUSTOMER_ADMIN, "Runs a customer company and all its workspaces");
        BUILT_INS.put(BuiltInRoles.WORKSPACE_MANAGER, "Runs one workspace");
        BUILT_INS.put(BuiltInRoles.TEAM_LEAD, "Leads a team in a workspace");
        BUILT_INS.put(BuiltInRoles.AGENT, "Handles conversations in a workspace");
    }

    private final RoleRepository roles;
    private final KnownAuthoritiesRepository known;
    private final RoleCache cache;

    public RoleSeeder(RoleRepository roles, KnownAuthoritiesRepository known, RoleCache cache) {
        this.roles = roles;
        this.known = known;
        this.cache = cache;
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

            KnownAuthorities state = known.findById(KnownAuthorities.ID).orElseGet(KnownAuthorities::new);
            Set<String> seen = state.getCodes();
            for (Authority a : Authority.values()) {
                if (seen.contains(a.code())) continue;
                for (String roleName : a.defaultRoles()) {
                    roles.findByName(roleName).ifPresent(r -> {
                        r.getAuthorities().add(a.code());
                        roles.save(r);
                    });
                }
                log.info("added new authority {} to {}", a.code(), a.defaultRoles());
            }
            state.setCodes(Arrays.stream(Authority.values()).map(Authority::code).collect(Collectors.toSet()));
            known.save(state);
        } finally {
            cache.evictAll();
        }
    }
}
