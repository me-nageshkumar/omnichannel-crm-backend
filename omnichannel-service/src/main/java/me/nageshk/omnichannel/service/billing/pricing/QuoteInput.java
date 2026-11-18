package me.nageshk.omnichannel.service.billing.pricing;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import me.nageshk.omnichannel.common.billing.OrderKind;
import me.nageshk.omnichannel.common.billing.SubscriptionState;
import me.nageshk.omnichannel.db.billing.BillingProfile;
import me.nageshk.omnichannel.db.billing.BillingSettings;
import me.nageshk.omnichannel.db.billing.FeatureGroup;
import me.nageshk.omnichannel.db.billing.Invoice;
import me.nageshk.omnichannel.db.billing.Plan;
import me.nageshk.omnichannel.db.billing.Subscription;
import me.nageshk.omnichannel.service.billing.SubscriptionViews.AddOnRequest;

/**
 * Everything a price depends on, read by the caller so that pricing itself touches nothing.
 *
 * @param subscription the customer's subscription, or null when it has none
 * @param plan the plan asked for; for a renewal, the customer's plan as it is now
 * @param today the date in the billing zone
 * @param addOns NEW and CHANGE: the add-ons wanted
 * @param keepAddOnIds RENEWAL: the held add-ons to keep; null keeps all
 * @param addOn ADDON: the one being bought
 * @param staff whether platform staff ask (they may discount, renew a retired plan, change to any plan)
 * @param invoices an invoice by its id, for the credit on what it billed
 * @param topUpAmount TOPUP: the rupees to add to the wallet, before tax
 */
public record QuoteInput(OrderKind kind, Subscription subscription, SubscriptionState state, Plan plan,
        Map<String, FeatureGroup> groups, BillingSettings settings, BillingProfile customer, ZoneId zone,
        LocalDate today, LocalDate startDate, List<AddOnRequest> addOns, List<String> keepAddOnIds, AddOnRequest addOn,
        BigDecimal extraDiscountPercent, boolean staff, Function<String, Invoice> invoices, BigDecimal topUpAmount) {

    /** A request that tops nothing up. */
    public QuoteInput(OrderKind kind, Subscription subscription, SubscriptionState state, Plan plan,
            Map<String, FeatureGroup> groups, BillingSettings settings, BillingProfile customer, ZoneId zone,
            LocalDate today, LocalDate startDate, List<AddOnRequest> addOns, List<String> keepAddOnIds, AddOnRequest addOn,
            BigDecimal extraDiscountPercent, boolean staff, Function<String, Invoice> invoices) {
        this(kind, subscription, state, plan, groups, settings, customer, zone, today, startDate, addOns, keepAddOnIds,
                addOn, extraDiscountPercent, staff, invoices, null);
    }
}
