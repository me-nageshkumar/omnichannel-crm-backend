package me.nageshk.omnichannel.security.entitlement;

import java.time.Clock;
import java.util.HashSet;
import java.util.Set;
import me.nageshk.omnichannel.db.billing.FeatureGroup;
import me.nageshk.omnichannel.db.billing.FeatureGroupRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Creates the built-in feature groups that are missing. An existing group is the staff's to edit and is left as it
 * is, with one exception: Core always gets back the built-in authorities it must hold, so a release that adds one
 * reaches installations that already have the group.
 */
@Component
@Order(2)
public class FeatureGroupSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(FeatureGroupSeeder.class);

    private final FeatureGroupRepository groups;
    private final Clock clock;

    public FeatureGroupSeeder(FeatureGroupRepository groups, Clock clock) {
        this.groups = groups;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        seed();
    }

    public void seed() {
        for (SeedGroups.Seed seed : SeedGroups.all()) {
            FeatureGroup existing = groups.findById(seed.code()).orElse(null);
            if (existing == null) {
                FeatureGroup g = new FeatureGroup();
                g.setCode(seed.code());
                g.setName(seed.name());
                g.setDescription(seed.description());
                g.setCategory(seed.category());
                g.setAuthorities(new HashSet<>(SeedGroups.authoritiesOf(seed)));
                g.setRequires(new HashSet<>(seed.requires()));
                g.setBuiltIn(true);
                g.setSortOrder(seed.sortOrder());
                g.setCreatedAt(clock.instant());
                g.setCreatedBy("system");
                groups.save(g);
                log.info("seeded feature group {}", seed.code());
            } else if (SeedGroups.CORE.equals(seed.code())) {
                Set<String> missing = new HashSet<>(SeedGroups.coreBuiltIns());
                missing.removeAll(existing.getAuthorities());
                if (missing.isEmpty()) continue;
                existing.getAuthorities().addAll(missing);
                existing.setUpdatedAt(clock.instant());
                existing.setUpdatedBy("system");
                groups.save(existing);
                log.info("added {} to the core feature group", missing);
            }
        }
    }
}
