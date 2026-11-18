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
 * Creates the built-in feature groups that are missing. An existing group is the staff's to edit: it keeps its name,
 * its requirements and whatever authorities the staff took out, but a built-in authority a later release adds to it
 * reaches it once (each group records what it has been given). Core also always gets back the built-in authorities it
 * must hold.
 */
@Component
@Order(2)
public class FeatureGroupSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(FeatureGroupSeeder.class);

    /**
     * Built-in authorities released after groups started recording what they were given. A group stored before that
     * counts every other built-in authority of its seed as given already, so the staff's earlier removals stand.
     */
    static final Set<String> RELEASED_SINCE_TRACKING = Set.of("inbox:view-bot");

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
                g.setReleasedAuthorities(new HashSet<>(SeedGroups.authoritiesOf(seed)));
                g.setBuiltIn(true);
                g.setSortOrder(seed.sortOrder());
                g.setCreatedAt(clock.instant());
                g.setCreatedBy("system");
                groups.save(g);
                log.info("seeded feature group {}", seed.code());
            } else {
                Set<String> builtIn = SeedGroups.authoritiesOf(seed);
                Set<String> given = existing.getReleasedAuthorities();
                if (given == null) {
                    given = new HashSet<>(builtIn);
                    given.removeAll(RELEASED_SINCE_TRACKING);
                }
                Set<String> added = new HashSet<>(builtIn);
                added.removeAll(given);
                added.removeAll(existing.getAuthorities());
                if (SeedGroups.CORE.equals(seed.code())) {
                    Set<String> missing = new HashSet<>(SeedGroups.coreBuiltIns());
                    missing.removeAll(existing.getAuthorities());
                    added.addAll(missing);
                }
                Set<String> nowGiven = new HashSet<>(given);
                nowGiven.addAll(builtIn);
                if (added.isEmpty() && nowGiven.equals(existing.getReleasedAuthorities())) continue;
                existing.getAuthorities().addAll(added);
                existing.setReleasedAuthorities(nowGiven);
                // only a change to what the group grants is an edit the staff should see
                if (!added.isEmpty()) {
                    existing.setUpdatedAt(clock.instant());
                    existing.setUpdatedBy("system");
                }
                groups.save(existing);
                if (!added.isEmpty()) log.info("added {} to the {} feature group", added, seed.code());
            }
        }
    }
}
