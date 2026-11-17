package me.nageshk.omnichannel.service.billing.pricing;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;
import me.nageshk.omnichannel.common.api.ValidationException;
import me.nageshk.omnichannel.common.billing.OrderKind;
import me.nageshk.omnichannel.db.billing.BillingSettings;
import me.nageshk.omnichannel.db.billing.FeatureGroup;
import me.nageshk.omnichannel.db.billing.Invoice;
import me.nageshk.omnichannel.db.billing.OrderCredit;
import me.nageshk.omnichannel.db.billing.OrderTarget;
import me.nageshk.omnichannel.db.billing.Subscription;
import me.nageshk.omnichannel.db.billing.TermSegment;
import me.nageshk.omnichannel.security.entitlement.SubscriptionClock;
import me.nageshk.omnichannel.service.billing.money.Gst;
import me.nageshk.omnichannel.service.billing.money.Money;
import me.nageshk.omnichannel.service.billing.pricing.UnusedCredit.Component;

/**
 * What cancelling an add-on or the plan gives back. Nothing is charged; the result is the credit and the
 * subscription as it will be. Pure, like {@link OrderPricing}.
 */
public final class CancellationPricing {

    private CancellationPricing() {}

    /**
     * @param creditTaxable the amount, before tax, staff choose to credit instead of the unused part; null for the
     *     unused part
     * @param now the moment, for the term in force; {@code today} is its date in the billing zone
     */
    public static Quote addOn(Subscription sub, String addOnId, BigDecimal creditTaxable, Instant now, LocalDate today,
            ZoneId zone, Function<String, Invoice> invoices, BillingSettings settings, Map<String, FeatureGroup> groups) {
        if (sub == null || sub.getCurrent() == null) throw new ValidationException("addOnId", "The customer has no plan");
        boolean held = false;
        for (TermSegment seg : new TermSegment[] {sub.getCurrent(), sub.getNext()}) {
            if (seg != null && seg.getAddOns().stream().anyMatch(a -> a.getId().equals(addOnId))) held = true;
        }
        if (!held) throw new ValidationException("addOnId", "The customer does not hold this add-on");
        // another add-on that needs this one would be left without what it stands on
        for (TermSegment seg : new TermSegment[] {sub.getCurrent(), sub.getNext()}) {
            if (seg == null) continue;
            String going = seg.getAddOns().stream().filter(a -> a.getId().equals(addOnId) && a.getGroupCode() != null)
                    .map(a -> a.getGroupCode()).findFirst().orElse(null);
            if (going == null) continue;
            for (var other : seg.getAddOns()) {
                FeatureGroup g = other.getGroupCode() == null || other.getId().equals(addOnId) ? null
                        : groups.get(other.getGroupCode());
                if (g != null && g.getRequires().contains(going)) {
                    FeatureGroup needed = groups.get(going);
                    throw new ValidationException("addOnId",
                            g.getName() + " needs " + (needed == null ? going : needed.getName()) + "; cancel it first");
                }
            }
        }
        List<OrderCredit> credits = credits(sub, today, zone, invoices, c -> addOnId.equals(c.addOnId()), creditTaxable);
        TermSegment after = OrderPricing.copyOf(SubscriptionClock.segmentAt(sub, now));
        after.getAddOns().removeIf(a -> a.getId().equals(addOnId));
        OrderTarget target = new OrderTarget();
        target.setSegmentAfter(after);
        target.setRemoveAddOnId(addOnId);
        return quote(OrderKind.CANCEL_ADDON, sub, credits, target, settings);
    }

    /** The plan ends today: no grace, the renewal (if one was paid) is dropped, and everything unused comes back. */
    public static Quote plan(Subscription sub, BigDecimal creditTaxable, Instant now, LocalDate today, ZoneId zone,
            Function<String, Invoice> invoices, BillingSettings settings) {
        if (sub == null || sub.getCurrent() == null) throw new ValidationException("addOnId", "The customer has no plan");
        if (!now.isBefore(SubscriptionClock.lastEnd(sub))) {
            throw new ValidationException("addOnId", "The plan has already ended; there is nothing to cancel");
        }
        List<OrderCredit> credits = credits(sub, today, zone, invoices, c -> true, creditTaxable);
        TermSegment after = OrderPricing.copyOf(SubscriptionClock.segmentAt(sub, now));
        Instant end = today.atStartOfDay(zone).toInstant();
        after.setTermEnd(end.isBefore(after.getTermStart()) ? after.getTermStart() : end);
        OrderTarget target = new OrderTarget();
        target.setSegmentAfter(after);
        target.setCancelPlan(true);
        return quote(OrderKind.CANCEL_PLAN, sub, credits, target, settings);
    }

    /**
     * The credits for the chosen parts: their unused value, or the amount staff chose, taken from the parts in
     * order (the plan, its add-ons, then a paid renewal), from each at most what was paid for it.
     */
    static List<OrderCredit> credits(Subscription sub, LocalDate today, ZoneId zone, Function<String, Invoice> invoices,
            Predicate<Component> which, BigDecimal creditTaxable) {
        if (creditTaxable == null) return UnusedCredit.of(sub, today, zone, invoices, which, null);
        if (creditTaxable.signum() < 0) throw new ValidationException("creditTaxable", "cannot be negative");
        if (creditTaxable.stripTrailingZeros().scale() > 2) throw new ValidationException("creditTaxable", "at most 2 decimals");
        BigDecimal left = creditTaxable;
        BigDecimal most = Money.ZERO;
        Map<String, BigDecimal> byInvoice = new LinkedHashMap<>();
        for (Component c : UnusedCredit.components(sub)) {
            if (c.invoiceId() == null || c.paid().signum() <= 0 || !which.test(c)) continue;
            most = most.add(c.paid());
            BigDecimal take = left.min(c.paid());
            if (take.signum() > 0) byInvoice.merge(c.invoiceId(), take, BigDecimal::add);
            left = left.subtract(take);
        }
        if (left.signum() > 0) {
            throw new ValidationException("creditTaxable", "At most " + Money.round(most).toPlainString() + " was paid for this");
        }
        List<OrderCredit> credits = UnusedCredit.forAmounts(byInvoice, invoices);
        BigDecimal given = Money.ZERO;
        for (OrderCredit c : credits) given = given.add(c.taxable());
        if (given.compareTo(Money.round(creditTaxable)) < 0) {
            throw new ValidationException("creditTaxable",
                    "Only " + given.toPlainString() + " is left uncredited on the invoices that billed this");
        }
        return credits;
    }

    private static Quote quote(OrderKind kind, Subscription sub, List<OrderCredit> credits, OrderTarget target,
            BillingSettings settings) {
        BigDecimal fromNotes = Money.ZERO;
        for (OrderCredit c : credits) fromNotes = fromNotes.add(c.total());
        return new Quote(kind, new ArrayList<>(), BigDecimal.ZERO, Money.ZERO,
                Gst.of(Money.ZERO, settings.getGstRatePercent(), false), Money.ZERO, List.copyOf(credits), fromNotes,
                Money.round(Money.zeroIfNull(sub.getCreditBalance())), Money.ZERO, Money.ZERO, target, null);
    }
}
