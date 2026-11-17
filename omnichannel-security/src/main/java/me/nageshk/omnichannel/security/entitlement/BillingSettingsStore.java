package me.nageshk.omnichannel.security.entitlement;

import java.time.ZoneId;
import me.nageshk.omnichannel.db.billing.BillingSettings;
import me.nageshk.omnichannel.db.billing.BillingSettingsRepository;
import org.springframework.stereotype.Component;

/** The billing settings, held in memory; {@link #evict()} after every save. Single-instance only. */
@Component
public class BillingSettingsStore {

    private final BillingSettingsRepository repository;
    private volatile BillingSettings cached;

    public BillingSettingsStore(BillingSettingsRepository repository) {
        this.repository = repository;
    }

    /** The stored settings, or the defaults when none were saved yet. */
    public BillingSettings get() {
        BillingSettings current = cached;
        if (current == null) {
            current = repository.findById(BillingSettings.ID).orElseGet(BillingSettings::new);
            cached = current;
        }
        return current;
    }

    public ZoneId zone() {
        return ZoneId.of(get().getZone());
    }

    public void evict() {
        cached = null;
    }
}
