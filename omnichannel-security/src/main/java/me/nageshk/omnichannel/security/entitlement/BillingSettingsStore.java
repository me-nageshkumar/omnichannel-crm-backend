package me.nageshk.omnichannel.security.entitlement;

import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicLong;
import me.nageshk.omnichannel.db.billing.BillingSettings;
import me.nageshk.omnichannel.db.billing.BillingSettingsRepository;
import org.springframework.stereotype.Component;

/**
 * The billing settings, held in memory; {@link #evict()} after every save. A load that overlaps an eviction is used
 * once but not cached, as in {@code RoleCache}. Single-instance only.
 */
@Component
public class BillingSettingsStore {

    private final BillingSettingsRepository repository;
    private final AtomicLong generation = new AtomicLong();
    private volatile BillingSettings cached;

    public BillingSettingsStore(BillingSettingsRepository repository) {
        this.repository = repository;
    }

    /** The stored settings, or the defaults when none were saved yet. */
    public BillingSettings get() {
        BillingSettings current = cached;
        if (current == null) {
            long loadedAt = generation.get();
            current = repository.findById(BillingSettings.ID).orElseGet(BillingSettings::new);
            // a read that overlapped a save is used once but not kept, so it cannot outlive that save
            synchronized (this) {
                if (generation.get() == loadedAt) cached = current;
            }
        }
        return current;
    }

    public ZoneId zone() {
        return ZoneId.of(get().getZone());
    }

    public synchronized void evict() {
        generation.incrementAndGet();
        cached = null;
    }
}
