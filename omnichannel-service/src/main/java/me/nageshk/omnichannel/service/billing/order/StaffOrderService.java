package me.nageshk.omnichannel.service.billing.order;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import me.nageshk.omnichannel.common.api.CodedException;
import me.nageshk.omnichannel.common.api.NotFoundException;
import me.nageshk.omnichannel.common.api.ValidationException;
import me.nageshk.omnichannel.common.billing.OrderKind;
import me.nageshk.omnichannel.db.billing.BillingOrder;
import me.nageshk.omnichannel.db.billing.BillingSettings;
import me.nageshk.omnichannel.db.billing.InvoiceRepository;
import me.nageshk.omnichannel.db.billing.OrderTarget;
import me.nageshk.omnichannel.db.billing.Subscription;
import me.nageshk.omnichannel.db.billing.SubscriptionRepository;
import me.nageshk.omnichannel.db.billing.TermSegment;
import me.nageshk.omnichannel.db.tenant.CustomerRepository;
import me.nageshk.omnichannel.security.entitlement.BillingSettingsStore;
import me.nageshk.omnichannel.service.billing.money.Gst;
import me.nageshk.omnichannel.service.billing.money.Money;
import me.nageshk.omnichannel.service.billing.order.OrderViews.AdjustRequest;
import me.nageshk.omnichannel.service.billing.order.OrderViews.CancelRequest;
import me.nageshk.omnichannel.service.billing.order.OrderViews.OrderView;
import me.nageshk.omnichannel.service.billing.order.OrderViews.QuoteView;
import me.nageshk.omnichannel.service.billing.pricing.CancellationPricing;
import me.nageshk.omnichannel.service.billing.pricing.Quote;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * What staff do to a subscription by hand: move the end of a term, post account credit, cancel an add-on or the
 * plan. Each is an order like any other, so it is numbered, kept and shown in the history, but it is activated at
 * once: there is nothing to pay. Each needs a reason.
 */
@Service
public class StaffOrderService {

    private final SubscriptionRepository subscriptions;
    private final CustomerRepository customers;
    private final InvoiceRepository invoices;
    private final BillingSettingsStore settings;
    private final BillingNumbers numbers;
    private final OrderActivator activator;
    private final Clock clock;

    public StaffOrderService(SubscriptionRepository subscriptions, CustomerRepository customers,
            InvoiceRepository invoices, BillingSettingsStore settings, BillingNumbers numbers, OrderActivator activator,
            Clock clock) {
        this.subscriptions = subscriptions;
        this.customers = customers;
        this.invoices = invoices;
        this.settings = settings;
        this.numbers = numbers;
        this.activator = activator;
        this.clock = clock;
    }

    /** Whether the request posts account credit, which needs a further right than moving a date. */
    public static boolean postsCredit(AdjustRequest req) {
        return req != null && req.creditAmount() != null;
    }

    /**
     * @throws CodedException 409 NOT_ENOUGH_CREDIT when a negative posting is more than the customer holds
     */
    public OrderView adjust(String customerId, AdjustRequest req, String by) {
        requireCustomer(customerId);
        Map<String, String> errors = new LinkedHashMap<>();
        String reason = reason(req == null ? null : req.reason(), errors);
        LocalDate termEnd = req == null ? null : req.termEnd();
        BigDecimal credit = req == null ? null : req.creditAmount();
        if ((termEnd == null) == (credit == null)) {
            errors.put(termEnd == null ? "termEnd" : "creditAmount", "Give either a new end date or a credit amount");
        } else if (credit != null) {
            if (credit.signum() == 0) errors.put("creditAmount", "cannot be 0");
            else if (credit.stripTrailingZeros().scale() > 2) errors.put("creditAmount", "at most 2 decimals");
        }
        if (!errors.isEmpty()) throw new ValidationException(errors);

        return activator.activateNow(customerId, by, () -> {
            BillingSettings s = settings.get();
            ZoneId zone = ZoneId.of(s.getZone());
            Subscription sub = subscriptions.findByCustomerId(customerId).filter(x -> x.getCurrent() != null).orElse(null);
            String field = termEnd != null ? "termEnd" : "creditAmount";
            if (sub == null) throw new ValidationException(field, "The customer has no plan");
            OrderTarget target = new OrderTarget();
            if (termEnd != null) {
                TermSegment last = sub.getNext() != null ? sub.getNext() : sub.getCurrent();
                Instant end = termEnd.atStartOfDay(zone).toInstant();
                if (!end.isAfter(last.getTermStart())) throw new ValidationException("termEnd", "must be after the term's start");
                target.setNewTermEnd(end);
            } else {
                BigDecimal after = Money.zeroIfNull(sub.getCreditBalance()).add(credit);
                if (after.signum() < 0) {
                    throw new CodedException(HttpStatus.CONFLICT, "NOT_ENOUGH_CREDIT", null, "The customer holds only "
                            + Money.round(Money.zeroIfNull(sub.getCreditBalance())).toPlainString() + " of account credit");
                }
                target.setCreditPosting(Money.round(credit));
            }
            target.setSegmentAfter(sub.getCurrent());
            Quote nothing = new Quote(OrderKind.ADJUST, new ArrayList<>(), BigDecimal.ZERO, Money.ZERO,
                    Gst.of(Money.ZERO, s.getGstRatePercent(), false), Money.ZERO, new ArrayList<>(), Money.ZERO,
                    Money.round(Money.zeroIfNull(sub.getCreditBalance())), Money.ZERO, Money.ZERO, target, null);
            return order(customerId, nothing, sub, reason, by, s);
        });
    }

    /** The credit a cancellation would give. Nothing is stored. */
    public QuoteView cancellationQuote(String customerId, CancelRequest req) {
        requireCustomer(customerId);
        BillingSettings s = settings.get();
        Subscription sub = subscriptions.findByCustomerId(customerId).orElse(null);
        return OrderService.quoteView(price(sub, req, s), clock.instant());
    }

    /** Cancels an add-on, or with no add-on named the plan, and credits what the quote says. */
    public OrderView cancel(String customerId, CancelRequest req, String by) {
        requireCustomer(customerId);
        Map<String, String> errors = new LinkedHashMap<>();
        String reason = reason(req == null ? null : req.reason(), errors);
        if (!errors.isEmpty()) throw new ValidationException(errors);
        return activator.activateNow(customerId, by, () -> {
            BillingSettings s = settings.get();
            Subscription sub = subscriptions.findByCustomerId(customerId).orElse(null);
            return order(customerId, price(sub, req, s), sub, reason, by, s);
        });
    }

    private Quote price(Subscription sub, CancelRequest req, BillingSettings s) {
        ZoneId zone = ZoneId.of(s.getZone());
        Instant now = clock.instant();
        LocalDate today = now.atZone(zone).toLocalDate();
        String addOnId = req == null ? null : req.addOnId();
        BigDecimal creditTaxable = req == null ? null : req.creditTaxable();
        return addOnId == null || addOnId.isBlank()
                ? CancellationPricing.plan(sub, creditTaxable, now, today, zone, id -> invoices.findById(id).orElse(null), s)
                : CancellationPricing.addOn(sub, addOnId, creditTaxable, now, today, zone,
                        id -> invoices.findById(id).orElse(null), s);
    }

    private BillingOrder order(String customerId, Quote q, Subscription sub, String reason, String by, BillingSettings s) {
        Instant now = clock.instant();
        BillingOrder o = new BillingOrder();
        o.setNumber(numbers.order());
        o.setCustomerId(customerId);
        o.setKind(q.kind());
        o.setLines(q.lines());
        o.setTaxable(q.taxable());
        o.setTax(q.tax());
        o.setTotal(q.total());
        o.setCredits(q.credits());
        o.setCreditFromNotes(q.creditFromNotes());
        o.setAccountCredit(q.accountCredit());
        o.setAccountCreditUsed(q.accountCreditUsed());
        o.setPayable(q.payable());
        TermSegment after = q.target().getSegmentAfter();
        if (after != null) {
            o.setPlanCode(after.getPlanCode());
            o.setPlanName(after.getTerms() == null ? null : after.getTerms().planName());
        }
        o.setTarget(q.target());
        o.setQuotedRevision(sub.getRevision());
        o.setQuotedTermRevision(sub.getTermRevision());
        o.setValidUntil(now.plus(s.getQuoteValidDays(), ChronoUnit.DAYS));
        o.setRaisedBy(by);
        o.setRaisedByStaff(true);
        o.setRaisedAt(now);
        o.setReason(reason);
        return o;
    }

    private void requireCustomer(String customerId) {
        if (!customers.existsById(customerId)) throw new NotFoundException("customer not found");
    }

    private static String reason(String raw, Map<String, String> errors) {
        String reason = raw == null ? "" : raw.trim();
        if (reason.isEmpty()) errors.put("reason", "Say why");
        else if (reason.length() > 300) errors.put("reason", "at most 300 characters");
        return reason;
    }
}
