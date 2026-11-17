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
import me.nageshk.omnichannel.db.billing.Invoice;
import me.nageshk.omnichannel.db.billing.OrderCredit;
import me.nageshk.omnichannel.db.billing.Subscription;
import me.nageshk.omnichannel.db.billing.SubscriptionAddOn;
import me.nageshk.omnichannel.db.billing.TermSegment;
import me.nageshk.omnichannel.service.billing.money.Gst;
import me.nageshk.omnichannel.service.billing.money.Money;
import me.nageshk.omnichannel.service.billing.money.Proration;
import org.bson.types.ObjectId;

/**
 * What a customer has paid for and not yet used: the days left of the current term, and the whole of a renewal that
 * has not begun. It comes back as one credit per invoice that billed it, with that invoice's tax, and never more
 * than the invoice still has uncredited. What was given free has no invoice and gives nothing back.
 */
public final class UnusedCredit {

    /**
     * One paid part of a subscription.
     *
     * @param addOnId null for the plan itself
     * @param next whether it belongs to a renewal that has not begun
     */
    public record Component(String invoiceId, BigDecimal paid, Instant coveredFrom, Instant end, boolean next,
            String addOnId, String what) {}

    private UnusedCredit() {}

    /** Every paid part of the subscription, the current term's first. */
    public static List<Component> components(Subscription sub) {
        List<Component> out = new ArrayList<>();
        add(out, sub.getCurrent(), false);
        add(out, sub.getNext(), true);
        return out;
    }

    private static void add(List<Component> out, TermSegment seg, boolean next) {
        if (seg == null) return;
        String plan = seg.getTerms() == null ? "the plan" : seg.getTerms().planName();
        out.add(new Component(seg.getPlanInvoiceId(), Money.zeroIfNull(seg.getPlanPaid()), seg.getTermStart(),
                seg.getTermEnd(), next, null, plan));
        for (SubscriptionAddOn a : seg.getAddOns()) {
            out.add(new Component(a.getInvoiceId(), Money.zeroIfNull(a.getPaid()),
                    a.getCoveredFrom() == null ? seg.getTermStart() : a.getCoveredFrom(), seg.getTermEnd(), next, a.getId(),
                    "an add-on"));
        }
    }

    /** The unused part of one component as of today, before tax. */
    public static BigDecimal unused(Component c, LocalDate today, ZoneId zone) {
        if (c.next()) return Money.round(c.paid());
        LocalDate end = c.end().atZone(zone).toLocalDate();
        LocalDate from = c.coveredFrom().atZone(zone).toLocalDate();
        return Proration.part(c.paid(), Proration.days(today, end), Proration.days(from, end));
    }

    /**
     * The credits for the chosen components.
     *
     * @param which the components to credit
     * @param overrideTaxable for a single component: the amount to credit instead of the unused part, at most what
     *     was paid for it; null for the unused part
     */
    public static List<OrderCredit> of(Subscription sub, LocalDate today, ZoneId zone, Function<String, Invoice> invoices,
            Predicate<Component> which, BigDecimal overrideTaxable) {
        Map<String, BigDecimal> byInvoice = new LinkedHashMap<>();
        for (Component c : components(sub)) {
            if (c.invoiceId() == null || c.paid().signum() <= 0 || !which.test(c)) continue;
            BigDecimal amount = overrideTaxable == null ? unused(c, today, zone) : overrideTaxable.min(c.paid());
            if (amount.signum() > 0) byInvoice.merge(c.invoiceId(), amount, BigDecimal::add);
        }
        return forAmounts(byInvoice, invoices);
    }

    /**
     * One credit per invoice for the amounts given, each with its invoice's tax and never more than the invoice
     * still has uncredited.
     *
     * @param byInvoice the amount before tax to give back on each invoice, by invoice id
     */
    public static List<OrderCredit> forAmounts(Map<String, BigDecimal> byInvoice, Function<String, Invoice> invoices) {
        List<OrderCredit> credits = new ArrayList<>();
        byInvoice.forEach((invoiceId, amount) -> {
            Invoice invoice = invoices.apply(invoiceId);
            if (invoice == null) return;
            BigDecimal left = invoice.getTaxable().subtract(Money.zeroIfNull(invoice.getCredited()));
            BigDecimal taxable = Money.round(amount.min(left));
            if (taxable.signum() <= 0) return;
            var tax = Gst.of(taxable, invoice.getTax().ratePercent(), invoice.getTax().interState());
            credits.add(new OrderCredit(new ObjectId().toHexString(), invoiceId, invoice.getNumber(),
                    "Unused value on invoice " + invoice.getNumber(), taxable, tax, taxable.add(tax.total())));
        });
        return credits;
    }
}
