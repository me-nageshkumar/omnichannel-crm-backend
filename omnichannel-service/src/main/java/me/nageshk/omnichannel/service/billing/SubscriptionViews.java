package me.nageshk.omnichannel.service.billing;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import me.nageshk.omnichannel.common.billing.BillingPeriod;
import me.nageshk.omnichannel.common.billing.LimitKey;
import me.nageshk.omnichannel.common.billing.LimitScope;
import me.nageshk.omnichannel.common.billing.SubscriptionState;
import me.nageshk.omnichannel.db.billing.AddOnKind;
import me.nageshk.omnichannel.db.billing.WithoutPlan;

/** What the billing API takes and returns for a customer's subscription and the billing settings. */
public final class SubscriptionViews {

    private SubscriptionViews() {}

    public record AddOnRequest(AddOnKind kind, String groupCode, LimitKey limitKey, Integer quantity) {}

    public record PlanRef(String code, String name, BillingPeriod period, int rank) {}

    /** @param source DEFAULT (in every plan), PLAN or ADDON */
    public record GroupRef(String code, String name, String source) {}

    public record AddOnView(String id, AddOnKind kind, String groupCode, LimitKey limitKey, int quantity) {}

    /** @param limit null when unlimited; @param used null for a limit that is not counted across the customer */
    public record LimitView(LimitKey key, String label, LimitScope scope, Integer limit, Long used) {}

    /**
     * The dates are midnights in {@code zone}; {@code termEnd} is the first moment after the last paid day.
     *
     * @param creditBalance money the platform owes the customer, tax included
     * @param cancelled the plan was cancelled: no grace, read-only from its end until a plan is bought again
     */
    public record SubscriptionView(SubscriptionState state, boolean readOnly, PlanRef plan, Instant termStart,
            Instant termEnd, Instant graceEnd, String zone, List<GroupRef> groups, List<AddOnView> addOns,
            List<LimitView> limits, long revision, BigDecimal creditBalance, NextTerm next, boolean cancelled) {}

    /** A renewal that is paid for and has not begun. */
    public record NextTerm(String planName, Instant termStart, Instant termEnd) {}

    public record LimitKeyView(LimitKey key, LimitScope scope, String label) {}

    /** A field that is null keeps what is stored; a blank text clears it. */
    public record SettingsRequest(String zone, Integer graceDays, WithoutPlan customersWithoutPlan, Long version,
            String supplierLegalName, String supplierAddress, String supplierStateCode, String supplierGstin,
            String sacCode, BigDecimal gstRatePercent, String invoicePrefix, String creditNotePrefix,
            Integer quoteValidDays, Integer renewalWindowDays, String paymentInstructions) {

        public SettingsRequest(String zone, Integer graceDays, WithoutPlan customersWithoutPlan, Long version) {
            this(zone, graceDays, customersWithoutPlan, version, null, null, null, null, null, null, null, null, null,
                    null, null);
        }
    }

    /** {@code complete}: the supplier details an invoice needs are filled in. */
    public record SettingsView(String zone, int graceDays, WithoutPlan customersWithoutPlan, Long version,
            String supplierLegalName, String supplierAddress, String supplierStateCode, String supplierGstin,
            String sacCode, BigDecimal gstRatePercent, String invoicePrefix, String creditNotePrefix,
            int quoteValidDays, int renewalWindowDays, String paymentInstructions, boolean complete) {}

    public record ProfileRequest(String legalName, String address, String stateCode, String gstin) {}

    /** {@code complete}: enough to issue an invoice to the customer. */
    public record ProfileView(String legalName, String address, String stateCode, String gstin, boolean complete) {}

    public record StateView(String code, String name) {}
}
