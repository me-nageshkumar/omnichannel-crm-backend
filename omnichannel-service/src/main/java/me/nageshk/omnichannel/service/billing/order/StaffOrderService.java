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
import me.nageshk.omnichannel.common.api.WalletEmptyException;
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
import me.nageshk.omnichannel.service.billing.FeatureGroupService;
import me.nageshk.omnichannel.service.billing.money.Gst;
import me.nageshk.omnichannel.service.billing.money.Money;
import me.nageshk.omnichannel.service.billing.order.OrderViews.AdjustRequest;
import me.nageshk.omnichannel.service.billing.order.OrderViews.CancelRequest;
import me.nageshk.omnichannel.service.billing.order.OrderViews.OrderView;
import me.nageshk.omnichannel.service.billing.order.OrderViews.QuoteView;
import me.nageshk.omnichannel.service.billing.order.OrderViews.WalletRefundRequest;
import me.nageshk.omnichannel.service.billing.pricing.CancellationPricing;
import me.nageshk.omnichannel.service.billing.pricing.Quote;
import me.nageshk.omnichannel.service.billing.pricing.WalletRefundPricing;
import me.nageshk.omnichannel.service.billing.wallet.WalletQueryService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * What staff do to a subscription by hand: move the end of a term, post account credit, cancel an add-on or the
 * plan. Each is an order like any other, so it is numbered, kept and shown in the history, but it is activated at
 * once: there is nothing to pay. Each needs a reason.
 */
@Service
public class StaffOrderService {

    private static final BigDecimal MOST_CREDIT = new BigDecimal("10000000");

    private final SubscriptionRepository subscriptions;
    private final CustomerRepository customers;
    private final InvoiceRepository invoices;
    private final BillingSettingsStore settings;
    private final BillingNumbers numbers;
    private final OrderActivator activator;
    private final Clock clock;
    private final FeatureGroupService groups;
    private final WalletQueryService walletQueries;

    public StaffOrderService(SubscriptionRepository subscriptions, CustomerRepository customers,
            InvoiceRepository invoices, BillingSettingsStore settings, BillingNumbers numbers, OrderActivator activator,
            Clock clock, FeatureGroupService groups, WalletQueryService walletQueries) {
        this.groups = groups;
        this.walletQueries = walletQueries;
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
            else if (credit.abs().compareTo(MOST_CREDIT) > 0) errors.put("creditAmount", "at most 1,00,00,000 at a time");
        }
        if (!errors.isEmpty()) throw new ValidationException(errors);

        return activator.activateNow(customerId, by, () -> {
            BillingSettings s = settings.get();
            ZoneId zone = ZoneId.of(s.getZone());
            Subscription sub = subscriptions.findByCustomerId(customerId).filter(x -> x.getCurrent() != null).orElse(null);
            String field = termEnd != null ? "termEnd" : "creditAmount";
            if (sub == null) throw new ValidationException(field, "The customer has no plan");
            OrderTarget target = new OrderTarget();
            target.setSegmentAfter(sub.getCurrent());
            if (termEnd != null) {
                if (sub.isCancelled()) throw new ValidationException("termEnd", "The plan was cancelled; start a new one");
                TermSegment last = sub.getNext() != null ? sub.getNext() : sub.getCurrent();
                // the order shows the term it moved
                target.setSegmentAfter(last);
                Instant end = termEnd.atStartOfDay(zone).toInstant();
                if (!end.isAfter(last.getTermStart())) throw new ValidationException("termEnd", "must be after the term's start");
                target.setNewTermEnd(end);
                // later terms end on the day of the month this one now ends on, so the next renewal is a full period
                target.setAnchorDay(termEnd.getDayOfMonth());
            } else {
                BigDecimal after = Money.zeroIfNull(sub.getCreditBalance()).add(credit);
                if (after.signum() < 0) {
                    throw new CodedException(HttpStatus.CONFLICT, "NOT_ENOUGH_CREDIT", null, "The customer holds only "
                            + Money.round(Money.zeroIfNull(sub.getCreditBalance())).toPlainString() + " of account credit");
                }
                target.setCreditPosting(Money.round(credit));
            }
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

    /** What a wallet refund of this amount would credit. Nothing is stored. */
    public QuoteView walletRefundQuote(String customerId, WalletRefundRequest req) {
        requireCustomer(customerId);
        return OrderService.quoteView(priceWalletRefund(customerId, req, settings.get()), clock.instant());
    }

    /**
     * Takes the amount out of the wallet and credits it, with the GST paid on it, to the account credit, from where
     * the money is paid back and recorded as a refund.
     *
     * @throws CodedException 409 NOT_ENOUGH_WALLET when the wallet or its top-up money does not cover the amount
     */
    public OrderView walletRefund(String customerId, WalletRefundRequest req, String by) {
        requireCustomer(customerId);
        Map<String, String> errors = new LinkedHashMap<>();
        String reason = reason(req == null ? null : req.reason(), errors);
        if (!errors.isEmpty()) throw new ValidationException(errors);
        try {
            return activator.activateNow(customerId, by, () -> {
                BillingSettings s = settings.get();
                return order(customerId, priceWalletRefund(customerId, req, s),
                        subscriptions.findByCustomerId(customerId).orElseThrow(), reason, by, s);
            });
        } catch (WalletEmptyException spent) {
            // the wallet was drawn on between the quote and the debit
            throw new CodedException(HttpStatus.CONFLICT, "NOT_ENOUGH_WALLET", null, "The wallet holds less than that");
        }
    }

    private Quote priceWalletRefund(String customerId, WalletRefundRequest req, BillingSettings s) {
        BigDecimal balance = walletQueries.view(customerId).balance();
        return WalletRefundPricing.quote(req == null ? null : req.amount(), balance, walletQueries.topUpInvoices(customerId),
                walletQueries.spent(customerId),
                subscriptions.findByCustomerId(customerId).orElse(null), s);
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
                        id -> invoices.findById(id).orElse(null), s, groups.byCode());
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
