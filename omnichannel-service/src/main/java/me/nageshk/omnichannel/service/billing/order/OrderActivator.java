package me.nageshk.omnichannel.service.billing.order;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.nageshk.omnichannel.common.api.CodedException;
import me.nageshk.omnichannel.common.api.ValidationException;
import me.nageshk.omnichannel.common.billing.OrderKind;
import me.nageshk.omnichannel.common.billing.OrderStatus;
import me.nageshk.omnichannel.common.billing.SubscriptionState;
import me.nageshk.omnichannel.db.billing.AddOnKind;
import me.nageshk.omnichannel.db.billing.BillingOrder;
import me.nageshk.omnichannel.db.billing.BillingOrderRepository;
import me.nageshk.omnichannel.db.billing.BillingProfile;
import me.nageshk.omnichannel.db.billing.BillingSettings;
import me.nageshk.omnichannel.db.billing.OrderPayment;
import me.nageshk.omnichannel.db.billing.Subscription;
import me.nageshk.omnichannel.db.billing.SubscriptionAddOn;
import me.nageshk.omnichannel.db.billing.SubscriptionRepository;
import me.nageshk.omnichannel.db.billing.TermSegment;
import me.nageshk.omnichannel.db.tenant.Customer;
import me.nageshk.omnichannel.db.tenant.CustomerRepository;
import me.nageshk.omnichannel.security.entitlement.BillingSettingsStore;
import me.nageshk.omnichannel.service.billing.BillingSettingsService;
import me.nageshk.omnichannel.service.billing.money.Gst;
import me.nageshk.omnichannel.service.billing.order.OrderViews.OrderView;
import me.nageshk.omnichannel.service.billing.order.OrderViews.PayRequest;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Puts an order into effect when staff record its payment. Recording moves the order to ACTIVATING in one step,
 * so it is paid once; the steps that follow are each repeatable and are ticked off on the order as they finish.
 * If the server stops between them, the order stays ACTIVATING and the next billing change for the customer (or
 * the ticker) finishes it first.
 */
@Service
public class OrderActivator {

    private static final Set<String> METHODS = Set.of("BANK_TRANSFER", "UPI", "CHEQUE", "CASH", "OTHER");

    private final BillingOrderRepository orders;
    private final SubscriptionRepository subscriptions;
    private final OrderService service;
    private final OrderSteps steps;
    private final BillingLocks locks;
    private final BillingSettingsStore settings;
    private final MongoTemplate mongo;
    private final Clock clock;
    private final CustomerRepository customers;

    public OrderActivator(BillingOrderRepository orders, SubscriptionRepository subscriptions, OrderService service,
            OrderSteps steps, BillingLocks locks, BillingSettingsStore settings, MongoTemplate mongo, Clock clock,
            CustomerRepository customers) {
        this.customers = customers;
        this.orders = orders;
        this.subscriptions = subscriptions;
        this.service = service;
        this.steps = steps;
        this.locks = locks;
        this.settings = settings;
        this.mongo = mongo;
        this.clock = clock;
    }

    /**
     * Records the payment and activates the order.
     *
     * @throws CodedException 409 ORDER_NOT_OPEN when it is not waiting for payment, ORDER_EXPIRED when its quote
     *         has run out, ORDER_STALE when the subscription changed since it was priced
     */
    public OrderView pay(String orderId, PayRequest req, String by) {
        String customerId = service.require(orderId, Caller.staff(by)).getCustomerId();
        return locks.withCustomer(customerId, () -> {
            finishPending(customerId);
            BillingOrder o = service.require(orderId, Caller.staff(by));
            Instant now = clock.instant();
            if (o.getStatus() != OrderStatus.REQUESTED) throw notOpen();
            if (!now.isBefore(o.getValidUntil())) {
                throw new CodedException(HttpStatus.CONFLICT, "ORDER_EXPIRED", null,
                        "This quote has run out; raise the order again at today's price");
            }
            BillingSettings s = settings.get();
            ZoneId zone = ZoneId.of(s.getZone());
            OrderPayment payment = payment(o, req, by, now, zone);
            requireFresh(o, s, zone, now);
            requireSameTax(o, s);

            BillingOrder taken = mongo.findAndModify(
                    new Query(Criteria.where("_id").is(o.getId()).and("status").is(OrderStatus.REQUESTED)),
                    new Update().set("status", OrderStatus.ACTIVATING).set("payment", payment)
                            .set("activatingSince", now).inc("version", 1),
                    FindAndModifyOptions.options().returnNew(true), BillingOrder.class);
            if (taken == null) throw notOpen();
            return service.view(finish(taken), new HashMap<>());
        });
    }

    /**
     * For what staff do at once (an adjustment, a cancellation): builds the order under the customer's lease, once
     * anything interrupted is finished, stores it as paid with nothing to pay, and runs its steps.
     *
     * @param build makes the order from the customer's state as it is inside the lease
     */
    public OrderView activateNow(String customerId, String by, java.util.function.Supplier<BillingOrder> build) {
        return locks.withCustomer(customerId, () -> {
            finishPending(customerId);
            Instant now = clock.instant();
            BillingOrder o = build.get();
            o.setStatus(OrderStatus.ACTIVATING);
            o.setPayment(new OrderPayment(OrderPayment.NONE, null, null, null, by, now));
            o.setActivatingSince(now);
            BillingOrder saved = orders.save(o);
            try {
                return service.view(finish(saved), new HashMap<>());
            } catch (RuntimeException e) {
                // staff will try again: an order that changed nothing must not be finished later as a second one
                boolean applied = subscriptions.findByCustomerId(customerId)
                        .map(sub -> sub.getAppliedOrderIds().contains(saved.getId())).orElse(false);
                if (!applied) {
                    mongo.updateFirst(
                            new Query(Criteria.where("_id").is(saved.getId()).and("status").is(OrderStatus.ACTIVATING)),
                            new Update().set("status", OrderStatus.CANCELLED).inc("version", 1), BillingOrder.class);
                }
                throw e;
            }
        });
    }

    /** Finishes every activation of the customer that was started and not completed, oldest first. */
    public void finishPending(String customerId) {
        locks.withCustomer(customerId, () -> {
            orders.findByCustomerIdAndStatusOrderByRaisedAtAscIdAsc(customerId, OrderStatus.ACTIVATING)
                    .forEach(this::finish);
            return null;
        });
    }

    /** Runs the steps the order has not ticked off, then marks it activated. */
    private BillingOrder finish(BillingOrder o) {
        if (!o.getSteps().contains(BillingOrder.STEP_APPLIED)) {
            steps.apply(o);
            tick(o, BillingOrder.STEP_APPLIED);
        }
        if (!o.getSteps().contains(BillingOrder.STEP_CREDIT_NOTED)) {
            steps.creditNotes(o);
            tick(o, BillingOrder.STEP_CREDIT_NOTED);
        }
        if (!o.getSteps().contains(BillingOrder.STEP_INVOICED)) {
            steps.invoice(o);
            tick(o, BillingOrder.STEP_INVOICED);
        }
        BillingOrder done = mongo.findAndModify(
                new Query(Criteria.where("_id").is(o.getId()).and("status").is(OrderStatus.ACTIVATING)),
                new Update().set("status", OrderStatus.ACTIVATED).inc("version", 1),
                FindAndModifyOptions.options().returnNew(true), BillingOrder.class);
        return done == null ? orders.findById(o.getId()).orElseThrow() : done;
    }

    private void tick(BillingOrder o, String step) {
        mongo.updateFirst(new Query(Criteria.where("_id").is(o.getId())),
                new Update().addToSet("steps", step).inc("version", 1), BillingOrder.class);
        o.getSteps().add(step);
    }

    /**
     * An order is applied only to the subscription it was priced on. A plan change needs the subscription exactly
     * as it was; an add-on only needs the same term, still running, without that group on it already.
     */
    private void requireFresh(BillingOrder o, BillingSettings s, ZoneId zone, Instant now) {
        Subscription sub = subscriptions.findByCustomerId(o.getCustomerId()).orElse(null);
        boolean hasPlan = sub != null && sub.getCurrent() != null;
        long revision = sub == null ? 0 : sub.getRevision();
        boolean fresh;
        if (o.getKind() == OrderKind.TOPUP) {
            // money into the wallet does not depend on the plan it was asked under
            fresh = true;
        } else if (o.getKind() == OrderKind.ADDON) {
            TermSegment priced = o.getTarget().getSegmentAfter();
            fresh = hasPlan && sub.getTermRevision() == o.getQuotedTermRevision() && now.isBefore(priced.getTermEnd())
                    && !alreadyHeld(sub, priced);
        } else if (o.getKind() == OrderKind.NEW) {
            fresh = revision == o.getQuotedRevision()
                    && (!hasPlan || OrderService.stateOf(sub, s, zone, now) == SubscriptionState.EXPIRED);
        } else {
            fresh = hasPlan && revision == o.getQuotedRevision();
        }
        // the account credit the order counts on, beyond what its own credit notes bring, must still be there
        java.math.BigDecimal needed = o.getAccountCreditUsed().subtract(o.getCreditFromNotes());
        java.math.BigDecimal held = sub == null || sub.getCreditBalance() == null ? java.math.BigDecimal.ZERO : sub.getCreditBalance();
        if (needed.signum() > 0 && held.compareTo(needed) < 0) fresh = false;
        if (!fresh) {
            throw new CodedException(HttpStatus.CONFLICT, "ORDER_STALE", null,
                    "The customer's plan changed since this order was priced; raise it again");
        }
    }

    /**
     * An order that will be invoiced was taxed for the supplier and the rate of the day it was priced; the invoice
     * is issued with today's. If they no longer agree, or the supplier details are incomplete, it is not paid.
     */
    private void requireSameTax(BillingOrder o, BillingSettings s) {
        if (o.getInvoiceId() == null || o.getTax() == null) return;
        if (!s.supplierComplete()) throw BillingSettingsService.notSetUp("supplier details in billing settings");
        String customerState = customers.findById(o.getCustomerId()).map(Customer::getBilling)
                .map(BillingProfile::stateCode).orElse(null);
        boolean interState = Gst.interState(s.getSupplierStateCode(), customerState);
        if (s.getGstRatePercent().compareTo(o.getTax().ratePercent()) != 0 || interState != o.getTax().interState()) {
            throw new CodedException(HttpStatus.CONFLICT, "ORDER_STALE", null,
                    "The GST rate or the supplier's state changed since this order was priced; raise it again");
        }
    }

    /** Whether the group the order adds is on the customer's terms by now (another order added it). */
    private static boolean alreadyHeld(Subscription sub, TermSegment priced) {
        List<SubscriptionAddOn> pricedAddOns = priced.getAddOns();
        SubscriptionAddOn bought = pricedAddOns.get(pricedAddOns.size() - 1);
        if (bought.getKind() != AddOnKind.GROUP) return false;
        for (TermSegment seg : new TermSegment[] {sub.getCurrent(), sub.getNext()}) {
            if (seg == null || !seg.getTermStart().equals(priced.getTermStart())) continue;
            if (seg.getAddOns().stream().anyMatch(a -> a.getKind() == AddOnKind.GROUP
                    && bought.getGroupCode().equals(a.getGroupCode()))) {
                return true;
            }
        }
        return false;
    }

    private static OrderPayment payment(BillingOrder o, PayRequest req, String by, Instant now, ZoneId zone) {
        Map<String, String> errors = new LinkedHashMap<>();
        String method = req == null || req.method() == null ? "" : req.method().trim();
        String reference = req == null || req.reference() == null ? "" : req.reference().trim();
        String note = req == null || req.note() == null ? "" : req.note().trim();
        LocalDate receivedOn = req == null ? null : req.receivedOn();
        if (note.length() > 300) errors.put("note", "at most 300 characters");
        if (o.getPayable().signum() == 0) {
            if (!method.isEmpty() && !OrderPayment.NONE.equals(method)) {
                errors.put("method", "Nothing is to be paid for this order; choose None");
            }
            if (!errors.isEmpty()) throw new ValidationException(errors);
            return new OrderPayment(OrderPayment.NONE, null, null, note.isEmpty() ? null : note, by, now);
        }
        if (OrderPayment.NONE.equals(method)) errors.put("method", "This order has an amount to pay");
        else if (!METHODS.contains(method)) errors.put("method", "must be one of BANK_TRANSFER, UPI, CHEQUE, CASH, OTHER");
        if (reference.isEmpty()) errors.put("reference", "is required");
        else if (reference.length() > 100) errors.put("reference", "at most 100 characters");
        if (receivedOn == null) errors.put("receivedOn", "is required");
        else if (receivedOn.isAfter(now.atZone(zone).toLocalDate())) errors.put("receivedOn", "cannot be in the future");
        else if (receivedOn.isBefore(now.atZone(zone).toLocalDate().minusDays(366))) errors.put("receivedOn", "cannot be more than a year ago");
        if (!errors.isEmpty()) throw new ValidationException(errors);
        return new OrderPayment(method, reference, receivedOn, note.isEmpty() ? null : note, by, now);
    }

    private static CodedException notOpen() {
        return new CodedException(HttpStatus.CONFLICT, "ORDER_NOT_OPEN", null,
                "This order is no longer waiting: it was paid, cancelled, rejected or has expired");
    }
}
