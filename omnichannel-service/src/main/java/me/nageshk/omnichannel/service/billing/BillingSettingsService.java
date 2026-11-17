package me.nageshk.omnichannel.service.billing;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import me.nageshk.omnichannel.common.api.CodedException;
import me.nageshk.omnichannel.common.billing.Gstin;
import me.nageshk.omnichannel.common.billing.IndianStates;
import me.nageshk.omnichannel.common.api.ValidationException;
import me.nageshk.omnichannel.db.billing.BillingChange;
import me.nageshk.omnichannel.db.billing.BillingSettings;
import me.nageshk.omnichannel.db.billing.BillingSettingsRepository;
import me.nageshk.omnichannel.security.entitlement.BillingSettingsStore;
import me.nageshk.omnichannel.security.entitlement.Entitlements;
import me.nageshk.omnichannel.service.billing.SubscriptionViews.SettingsRequest;
import me.nageshk.omnichannel.service.billing.SubscriptionViews.SettingsView;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** The platform's billing settings. They change every customer's state, so a save evicts all entitlements. */
@Service
public class BillingSettingsService {

    static final int MAX_GRACE_DAYS = 90;
    private static final Pattern PREFIX = Pattern.compile("[A-Z0-9]{1,3}");

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

        BillingSettings stored = repository.findById(BillingSettings.ID).orElseGet(BillingSettings::new);
        String state = req.supplierStateCode() == null ? stored.getSupplierStateCode() : blankToNull(req.supplierStateCode());
        String gstin = req.supplierGstin() == null ? stored.getSupplierGstin() : blankToNull(req.supplierGstin());
        if (state != null && IndianStates.nameOf(state).isEmpty()) errors.put("supplierStateCode", "is not a state");
        Gstin.problem(gstin, state).ifPresent(problem -> errors.put("supplierGstin", problem));
        if (req.supplierLegalName() != null && req.supplierLegalName().length() > 200) {
            errors.put("supplierLegalName", "at most 200 characters");
        }
        if (req.supplierAddress() != null && req.supplierAddress().length() > 500) {
            errors.put("supplierAddress", "at most 500 characters");
        }
        if (req.sacCode() != null && req.sacCode().length() > 20) errors.put("sacCode", "at most 20 characters");
        if (req.gstRatePercent() != null && (req.gstRatePercent().signum() < 0
                || req.gstRatePercent().compareTo(new BigDecimal("100")) > 0
                || req.gstRatePercent().stripTrailingZeros().scale() > 2)) {
            errors.put("gstRatePercent", "must be between 0 and 100, at most 2 decimals");
        }
        if (req.invoicePrefix() != null && !PREFIX.matcher(req.invoicePrefix()).matches()) {
            errors.put("invoicePrefix", "use 1 to 3 capital letters or digits: a document number may have 16 characters in all");
        }
        if (req.creditNotePrefix() != null && !PREFIX.matcher(req.creditNotePrefix()).matches()) {
            errors.put("creditNotePrefix", "use 1 to 3 capital letters or digits: a document number may have 16 characters in all");
        }
        if (req.quoteValidDays() != null && (req.quoteValidDays() < 1 || req.quoteValidDays() > 60)) {
            errors.put("quoteValidDays", "must be between 1 and 60");
        }
        if (req.renewalWindowDays() != null && (req.renewalWindowDays() < 0 || req.renewalWindowDays() > 120)) {
            errors.put("renewalWindowDays", "must be between 0 and 120");
        }
        if (req.paymentInstructions() != null && req.paymentInstructions().length() > 1000) {
            errors.put("paymentInstructions", "at most 1000 characters");
        }
        if (!errors.isEmpty()) throw new ValidationException(errors);

        BillingSettings s = stored;
        if (!Objects.equals(req.version(), s.getVersion())) {
            throw new OptimisticLockingFailureException("billing settings changed since they were read");
        }
        Map<String, Object> before = snapshot(s);
        s.setZone(zone);
        s.setGraceDays(req.graceDays());
        s.setCustomersWithoutPlan(req.customersWithoutPlan());
        if (req.supplierLegalName() != null) s.setSupplierLegalName(blankToNull(req.supplierLegalName()));
        if (req.supplierAddress() != null) s.setSupplierAddress(blankToNull(req.supplierAddress()));
        s.setSupplierStateCode(state);
        s.setSupplierGstin(gstin);
        if (req.sacCode() != null) s.setSacCode(blankToNull(req.sacCode()));
        if (req.gstRatePercent() != null) s.setGstRatePercent(req.gstRatePercent());
        if (req.invoicePrefix() != null) s.setInvoicePrefix(req.invoicePrefix());
        if (req.creditNotePrefix() != null) s.setCreditNotePrefix(req.creditNotePrefix());
        if (req.quoteValidDays() != null) s.setQuoteValidDays(req.quoteValidDays());
        if (req.renewalWindowDays() != null) s.setRenewalWindowDays(req.renewalWindowDays());
        if (req.paymentInstructions() != null) s.setPaymentInstructions(blankToNull(req.paymentInstructions()));
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
        return new SettingsView(s.getZone(), s.getGraceDays(), s.getCustomersWithoutPlan(), s.getVersion(),
                s.getSupplierLegalName(), s.getSupplierAddress(), s.getSupplierStateCode(), s.getSupplierGstin(),
                s.getSacCode(), s.getGstRatePercent(), s.getInvoicePrefix(), s.getCreditNotePrefix(),
                s.getQuoteValidDays(), s.getRenewalWindowDays(), s.getPaymentInstructions(), s.supplierComplete());
    }

    /**
     * The settings, once the supplier details an invoice needs are there.
     *
     * @throws CodedException 409 BILLING_NOT_SET_UP while they are missing
     */
    public BillingSettings requireSupplier() {
        BillingSettings s = store.get();
        if (!s.supplierComplete()) throw notSetUp("supplier details in billing settings");
        return s;
    }

    public static CodedException notSetUp(String missing) {
        return new CodedException(HttpStatus.CONFLICT, "BILLING_NOT_SET_UP", null,
                "Billing is not set up yet: " + missing, Map.of("missing", missing));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
