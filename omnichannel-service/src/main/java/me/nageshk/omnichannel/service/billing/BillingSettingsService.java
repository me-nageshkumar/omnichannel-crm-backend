package me.nageshk.omnichannel.service.billing;

import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import me.nageshk.omnichannel.common.api.ValidationException;
import me.nageshk.omnichannel.db.billing.BillingChange;
import me.nageshk.omnichannel.db.billing.BillingSettings;
import me.nageshk.omnichannel.db.billing.BillingSettingsRepository;
import me.nageshk.omnichannel.security.entitlement.BillingSettingsStore;
import me.nageshk.omnichannel.security.entitlement.Entitlements;
import me.nageshk.omnichannel.service.billing.SubscriptionViews.SettingsRequest;
import me.nageshk.omnichannel.service.billing.SubscriptionViews.SettingsView;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

/** The platform's billing settings. They change every customer's state, so a save evicts all entitlements. */
@Service
public class BillingSettingsService {

    static final int MAX_GRACE_DAYS = 90;

    private final BillingSettingsRepository repository;
    private final BillingSettingsStore store;
    private final Entitlements entitlements;
    private final BillingAudit audit;

    public BillingSettingsService(BillingSettingsRepository repository, BillingSettingsStore store,
            Entitlements entitlements, BillingAudit audit) {
        this.repository = repository;
        this.store = store;
        this.entitlements = entitlements;
        this.audit = audit;
    }

    public SettingsView get() {
        return view(repository.findById(BillingSettings.ID).orElseGet(BillingSettings::new));
    }

    public SettingsView update(SettingsRequest req, String by) {
        Map<String, String> errors = new LinkedHashMap<>();
        String zone = req.zone() == null ? "" : req.zone().trim();
        try {
            ZoneId.of(zone);
        } catch (RuntimeException e) {
            errors.put("zone", "is not a time zone, for example Asia/Kolkata");
        }
        if (req.graceDays() == null) errors.put("graceDays", "is required");
        else if (req.graceDays() < 0 || req.graceDays() > MAX_GRACE_DAYS) {
            errors.put("graceDays", "must be between 0 and " + MAX_GRACE_DAYS);
        }
        if (req.customersWithoutPlan() == null) errors.put("customersWithoutPlan", "is required");
        if (!errors.isEmpty()) throw new ValidationException(errors);

        BillingSettings s = repository.findById(BillingSettings.ID).orElseGet(BillingSettings::new);
        if (!Objects.equals(req.version(), s.getVersion())) {
            throw new OptimisticLockingFailureException("billing settings changed since they were read");
        }
        Map<String, Object> before = snapshot(s);
        s.setZone(zone);
        s.setGraceDays(req.graceDays());
        s.setCustomersWithoutPlan(req.customersWithoutPlan());
        BillingSettings saved = repository.save(s);
        // in force at once, whatever becomes of the history row
        store.evict();
        entitlements.evictAll();
        audit.record(BillingChange.SETTINGS, BillingSettings.ID, "UPDATED", "Edited", before, snapshot(saved), by);
        return view(saved);
    }

    private static Map<String, Object> snapshot(BillingSettings s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("zone", s.getZone());
        m.put("graceDays", s.getGraceDays());
        m.put("customersWithoutPlan", String.valueOf(s.getCustomersWithoutPlan()));
        return m;
    }

    private static SettingsView view(BillingSettings s) {
        return new SettingsView(s.getZone(), s.getGraceDays(), s.getCustomersWithoutPlan(), s.getVersion());
    }
}
