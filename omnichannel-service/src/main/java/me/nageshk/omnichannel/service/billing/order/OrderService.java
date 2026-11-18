package me.nageshk.omnichannel.service.billing.order;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.nageshk.omnichannel.common.api.CodedException;
import me.nageshk.omnichannel.common.api.ForbiddenException;
import me.nageshk.omnichannel.common.api.NotFoundException;
import me.nageshk.omnichannel.common.api.ValidationException;
import me.nageshk.omnichannel.common.billing.OrderKind;
import me.nageshk.omnichannel.common.billing.OrderStatus;
import me.nageshk.omnichannel.common.billing.SubscriptionState;
import me.nageshk.omnichannel.db.billing.BillingChange;
import me.nageshk.omnichannel.db.billing.BillingOrder;
import me.nageshk.omnichannel.db.billing.BillingOrderRepository;
import me.nageshk.omnichannel.db.billing.BillingProfile;
import me.nageshk.omnichannel.db.billing.BillingSettings;
import me.nageshk.omnichannel.db.billing.Invoice;
import me.nageshk.omnichannel.db.billing.InvoiceRepository;
import me.nageshk.omnichannel.db.billing.OrderCredit;
import me.nageshk.omnichannel.db.billing.OrderPayment;
import me.nageshk.omnichannel.db.billing.Plan;
import me.nageshk.omnichannel.db.billing.PlanRepository;
import me.nageshk.omnichannel.db.billing.PlanTerms;
import me.nageshk.omnichannel.db.billing.Subscription;
import me.nageshk.omnichannel.db.billing.SubscriptionRepository;
import me.nageshk.omnichannel.db.billing.TermSegment;
import me.nageshk.omnichannel.db.tenant.Customer;
import me.nageshk.omnichannel.db.tenant.CustomerRepository;
import me.nageshk.omnichannel.db.user.User;
import me.nageshk.omnichannel.db.user.UserRepository;
import me.nageshk.omnichannel.security.entitlement.SubscriptionClock;
import me.nageshk.omnichannel.service.billing.BillingAudit;
import me.nageshk.omnichannel.service.billing.BillingProfileService;
import me.nageshk.omnichannel.service.billing.BillingSettingsService;
import me.nageshk.omnichannel.service.billing.FeatureGroupService;
import me.nageshk.omnichannel.service.billing.order.OrderViews.OrderPage;
import me.nageshk.omnichannel.service.billing.order.OrderViews.OrderView;
import me.nageshk.omnichannel.service.billing.order.OrderViews.PaymentView;
import me.nageshk.omnichannel.service.billing.order.OrderViews.QuoteRequest;
import me.nageshk.omnichannel.service.billing.order.OrderViews.QuoteView;
import me.nageshk.omnichannel.service.billing.pricing.OrderPricing;
import me.nageshk.omnichannel.service.billing.pricing.Quote;
import me.nageshk.omnichannel.service.billing.pricing.QuoteInput;
import org.bson.types.ObjectId;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Quotes and orders. A quote prices a request against the customer as it is now; raising it stores those amounts as
 * an order, which changes nothing until staff record the payment. Raising happens under the customer's lease, so
 * two requests cannot both pass the "one open plan change" rule.
 */
@Service
public class OrderService {

    static final int PAGE_SIZE = 50;
    private static final Set<OrderKind> PRICED = EnumSet.of(OrderKind.NEW, OrderKind.RENEWAL, OrderKind.UPGRADE,
            OrderKind.CHANGE, OrderKind.ADDON, OrderKind.TOPUP);
    private static final Set<OrderKind> CUSTOMER_MAY = EnumSet.of(OrderKind.RENEWAL, OrderKind.UPGRADE, OrderKind.ADDON,
            OrderKind.TOPUP);

    private final BillingOrderRepository orders;
    private final SubscriptionRepository subscriptions;
    private final PlanRepository plans;
    private final CustomerRepository customers;
    private final UserRepository users;
    private final InvoiceRepository invoices;
    private final FeatureGroupService groups;
    private final BillingSettingsService settings;
    private final BillingProfileService profiles;
    private final BillingLocks locks;
    private final BillingNumbers numbers;
    private final BillingAudit audit;
    private final MongoTemplate mongo;
    private final Clock clock;

    public OrderService(BillingOrderRepository orders, SubscriptionRepository subscriptions, PlanRepository plans,
            CustomerRepository customers, UserRepository users, InvoiceRepository invoices, FeatureGroupService groups,
            BillingSettingsService settings, BillingProfileService profiles, BillingLocks locks, BillingNumbers numbers,
            BillingAudit audit, MongoTemplate mongo, Clock clock) {
        this.orders = orders;
        this.subscriptions = subscriptions;
        this.plans = plans;
        this.customers = customers;
        this.users = users;
        this.invoices = invoices;
        this.groups = groups;
        this.settings = settings;
        this.profiles = profiles;
        this.locks = locks;
        this.numbers = numbers;
        this.audit = audit;
        this.mongo = mongo;
        this.clock = clock;
    }

    /** What the request would cost now. Nothing is stored. */
    public QuoteView quote(String customerId, QuoteRequest req, Caller caller) {
        BillingSettings s = settings.requireSupplier();
        Quote q = OrderPricing.quote(input(customerId, req, caller, s, profiles.require(customerId)));
        return quoteView(q, clock.instant().plus(s.getQuoteValidDays(), ChronoUnit.DAYS));
    }

    /**
     * Stores the request with its amounts fixed.
     *
     * @throws CodedException 409 ORDER_PENDING when another open order of the customer already changes its plan;
     *         409 BILLING_NOT_SET_UP while the supplier details or the customer's billing profile are missing
     */
    public OrderView raise(String customerId, QuoteRequest req, Caller caller) {
        return locks.withCustomer(customerId, () -> {
            BillingSettings s = settings.requireSupplier();
            QuoteInput in = input(customerId, req, caller, s, profiles.require(customerId));
            Quote q = OrderPricing.quote(in);
            Instant now = clock.instant();
            if (q.kind().changesTerm() && hasOpenTermChange(customerId, now)) {
                throw new CodedException(HttpStatus.CONFLICT, "ORDER_PENDING", null,
                        "Another request to change this plan is waiting for payment; cancel it first");
            }
            if (q.kind() == OrderKind.ADDON && groupBought(q.target().getSegmentAfter()) != null) {
                String group = groupBought(q.target().getSegmentAfter());
                boolean asked = orders.findByCustomerIdAndStatusOrderByRaisedAtAscIdAsc(customerId, OrderStatus.REQUESTED)
                        .stream().anyMatch(x -> x.getKind() == OrderKind.ADDON && now.isBefore(x.getValidUntil())
                                && group.equals(groupBought(x.getTarget().getSegmentAfter())));
                if (asked) {
                    throw new CodedException(HttpStatus.CONFLICT, "ORDER_PENDING", null,
                            "A request for this add-on is already waiting for payment");
                }
            }
            BillingOrder o = new BillingOrder();
            o.setNumber(numbers.order());
            o.setCustomerId(customerId);
            o.setKind(q.kind());
            o.setStatus(OrderStatus.REQUESTED);
            o.setLines(q.lines());
            o.setExtraDiscountPercent(q.extraDiscountPercent());
            o.setExtraDiscountReason(q.extraDiscountPercent().signum() > 0 ? req.extraDiscountReason().trim() : null);
            o.setTaxable(q.taxable());
            o.setTax(q.tax());
            o.setTotal(q.total());
            o.setCredits(q.credits());
            o.setCreditFromNotes(q.creditFromNotes());
            o.setAccountCredit(q.accountCredit());
            o.setAccountCreditUsed(q.accountCreditUsed());
            o.setPayable(q.payable());
            TermSegment after = q.target().getSegmentAfter();
            o.setPlanCode(after.getPlanCode());
            o.setPlanName(after.getTerms().planName());
            o.setTarget(q.target());
            o.setQuotedRevision(in.subscription() == null ? 0 : in.subscription().getRevision());
            o.setQuotedTermRevision(in.subscription() == null ? 0 : in.subscription().getTermRevision());
            o.setValidUntil(now.plus(s.getQuoteValidDays(), ChronoUnit.DAYS));
            o.setRaisedBy(caller.userId());
            o.setRaisedByStaff(caller.staff());
            o.setRaisedAt(now);
            if (q.total().signum() > 0) o.setInvoiceId(new ObjectId().toHexString());
            BillingOrder saved = orders.save(o);
            audit.record(BillingChange.SUBSCRIPTION, customerId, "ORDER_RAISED",
                    "Order " + saved.getNumber() + " raised: " + summary(saved), null, null, caller.userId());
            return seenBy(caller, view(saved, new HashMap<>()));
        });
    }

    /** Newest first, as staff see them. */
    public List<OrderView> list(String customerId) {
        return list(customerId, Caller.staff(null));
    }

    /** Newest first, as the caller may see them. */
    public List<OrderView> list(String customerId, Caller caller) {
        if (!customers.existsById(customerId)) throw new NotFoundException("customer not found");
        Map<String, String> names = new HashMap<>();
        return orders.findByCustomerIdOrderByRaisedAtDescIdDesc(customerId).stream()
                .map(o -> seenBy(caller, view(o, names))).toList();
    }

    /**
     * An order as its customer may see it: the amounts and what became of it, without what staff wrote for
     * themselves (their reasons and payment notes) or their names.
     */
    static OrderView seenBy(Caller caller, OrderView v) {
        if (caller.staff()) return v;
        PaymentView p = v.payment() == null ? null
                : new PaymentView(v.payment().method(), v.payment().reference(), v.payment().receivedOn(), null, null,
                        v.payment().recordedAt());
        return new OrderView(v.id(), v.number(), v.customerId(), v.customerName(), v.status(), null,
                v.raisedByStaff() ? "Your account manager" : v.raisedByName(), v.raisedByStaff(), v.raisedAt(), null, null,
                p, v.invoiceId(), v.invoiceNumber(), v.creditNoteIds(), v.rejectedReason(), v.kind(), v.lines(),
                v.extraDiscountPercent(), v.taxable(), v.tax(), v.total(), v.credits(), v.creditFromNotes(),
                v.accountCredit(), v.accountCreditUsed(), v.payable(), v.planCode(), v.planName(), v.period(),
                v.termStart(), v.termEnd(), v.validUntil(), v.creditPosting(), v.newTermEnd());
    }

    /**
     * Every customer's orders, for staff. Open requests come oldest first, so the one waiting longest is on top;
     * everything else newest first.
     *
     * @param status null for all
     */
    public OrderPage queue(OrderStatus status, int page) {
        Instant now = clock.instant();
        Criteria where = new Criteria();
        if (status == OrderStatus.REQUESTED) {
            where = Criteria.where("status").is(OrderStatus.REQUESTED).and("validUntil").gt(now);
        } else if (status == OrderStatus.EXPIRED) {
            where = new Criteria().orOperator(Criteria.where("status").is(OrderStatus.EXPIRED),
                    Criteria.where("status").is(OrderStatus.REQUESTED).and("validUntil").lte(now));
        } else if (status != null) {
            where = Criteria.where("status").is(status);
        }
        int at = Math.max(page, 0);
        long total = mongo.count(new Query(where), BillingOrder.class);
        Sort sort = status == OrderStatus.REQUESTED ? Sort.by(Sort.Order.asc("raisedAt"), Sort.Order.asc("_id"))
                : Sort.by(Sort.Order.desc("raisedAt"), Sort.Order.desc("_id"));
        Map<String, String> names = new HashMap<>();
        List<OrderView> items = mongo.find(new Query(where).with(sort).skip((long) at * PAGE_SIZE).limit(PAGE_SIZE),
                BillingOrder.class).stream().map(o -> view(o, names)).toList();
        return new OrderPage(items, total, at);
    }

    /** @throws NotFoundException if there is none, or it belongs to another customer than the caller's */
    public OrderView get(String orderId, Caller caller) {
        return seenBy(caller, view(require(orderId, caller), new HashMap<>()));
    }

    /**
     * Withdraws an open request: staff any, a customer only one it raised itself.
     *
     * @throws CodedException 409 ORDER_NOT_OPEN when it is no longer waiting
     */
    public OrderView cancel(String orderId, Caller caller) {
        BillingOrder o = require(orderId, caller);
        if (!caller.staff() && o.isRaisedByStaff()) {
            throw new ForbiddenException("This order was raised by your account manager; ask them to cancel it");
        }
        BillingOrder done = close(o, OrderStatus.CANCELLED, null);
        audit.record(BillingChange.SUBSCRIPTION, o.getCustomerId(), "ORDER_CANCELLED",
                "Order " + o.getNumber() + " cancelled", null, null, caller.userId());
        return seenBy(caller, view(done, new HashMap<>()));
    }

    /** Staff turn a request down, saying why. */
    public OrderView reject(String orderId, String reason, String by) {
        String why = reason == null ? "" : reason.trim();
        if (why.isEmpty()) throw new ValidationException("reason", "is required");
        if (why.length() > 300) throw new ValidationException("reason", "at most 300 characters");
        BillingOrder o = require(orderId, Caller.staff(by));
        BillingOrder done = close(o, OrderStatus.REJECTED, why);
        audit.record(BillingChange.SUBSCRIPTION, o.getCustomerId(), "ORDER_REJECTED",
                "Order " + o.getNumber() + " rejected: " + why, null, null, by);
        return view(done, new HashMap<>());
    }

    // --- building blocks, shared with the options and with activation ---------------------------------------------

    /** Everything pricing needs, read now. */
    QuoteInput input(String customerId, QuoteRequest req, Caller caller, BillingSettings s, BillingProfile profile) {
        if (req == null || req.kind() == null) throw new ValidationException("kind", "is required");
        if (!PRICED.contains(req.kind())) throw new ValidationException("kind", "cannot be priced");
        if (!caller.staff() && !CUSTOMER_MAY.contains(req.kind())) {
            throw new ValidationException("kind", "Ask your account manager for this change");
        }
        BigDecimal extra = req.extraDiscountPercent();
        if (caller.staff() && extra != null && extra.signum() > 0) {
            String reason = req.extraDiscountReason() == null ? "" : req.extraDiscountReason().trim();
            if (reason.isEmpty()) throw new ValidationException("extraDiscountReason", "Say why the discount is given");
            if (reason.length() > 300) throw new ValidationException("extraDiscountReason", "at most 300 characters");
        }
        ZoneId zone = ZoneId.of(s.getZone());
        Instant now = clock.instant();
        Subscription sub = subscriptions.findByCustomerId(customerId).filter(x -> x.getCurrent() != null).orElse(null);
        SubscriptionState state = stateOf(sub, s, zone, now);
        Plan plan;
        if (req.kind() == OrderKind.RENEWAL) {
            plan = sub == null ? null : plans.findById(sub.getCurrent().getPlanCode()).orElse(null);
        } else if (req.kind() == OrderKind.ADDON || req.kind() == OrderKind.TOPUP) {
            plan = null;
        } else {
            if (req.planCode() == null || req.planCode().isBlank()) throw new ValidationException("planCode", "is required");
            plan = plans.findById(req.planCode().trim()).orElse(null);
        }
        return new QuoteInput(req.kind(), sub, state, plan, groups.byCode(), s, profile, zone,
                now.atZone(zone).toLocalDate(), req.startDate(), req.addOns(), req.keepAddOnIds(), req.addOn(), extra,
                caller.staff(), id -> id == null ? null : invoices.findById(id).orElse(null), req.topUpAmount());
    }

    /** The feature group an add-on order adds (pricing puts the new add-on last); null for extra units. */
    private static String groupBought(TermSegment priced) {
        if (priced == null || priced.getAddOns().isEmpty()) return null;
        return priced.getAddOns().get(priced.getAddOns().size() - 1).getGroupCode();
    }

    static SubscriptionState stateOf(Subscription sub, BillingSettings s, ZoneId zone, Instant now) {
        if (sub == null || sub.getCurrent() == null) return SubscriptionState.NONE;
        return SubscriptionClock.stateAt(sub, s.getGraceDays(), zone, now);
    }

    /** What the order's status reads as: a request past its validity is expired even before the ticker marks it. */
    public static OrderStatus effective(BillingOrder o, Instant now) {
        return o.getStatus() == OrderStatus.REQUESTED && !now.isBefore(o.getValidUntil()) ? OrderStatus.EXPIRED
                : o.getStatus();
    }

    BillingOrder require(String orderId, Caller caller) {
        BillingOrder o = orders.findById(orderId).orElseThrow(() -> new NotFoundException("order not found"));
        if (!caller.staff() && !o.getCustomerId().equals(caller.customerId())) throw new NotFoundException("order not found");
        return o;
    }

    private boolean hasOpenTermChange(String customerId, Instant now) {
        return orders.findByCustomerIdAndStatusOrderByRaisedAtAscIdAsc(customerId, OrderStatus.REQUESTED).stream()
                .anyMatch(o -> o.getKind().changesTerm() && now.isBefore(o.getValidUntil()));
    }

    /** Moves an open request to an end status in one step, so it cannot also be activated. */
    private BillingOrder close(BillingOrder o, OrderStatus to, String rejectedReason) {
        Update update = new Update().set("status", to).inc("version", 1);
        if (rejectedReason != null) update.set("rejectedReason", rejectedReason);
        BillingOrder done = mongo.findAndModify(
                new Query(Criteria.where("_id").is(o.getId()).and("status").is(OrderStatus.REQUESTED)
                        .and("validUntil").gt(clock.instant())),
                update, FindAndModifyOptions.options().returnNew(true), BillingOrder.class);
        if (done == null) {
            throw new CodedException(HttpStatus.CONFLICT, "ORDER_NOT_OPEN", null,
                    "This order is no longer waiting: it was paid, cancelled, rejected or has expired");
        }
        return done;
    }

    private static String summary(BillingOrder o) {
        return o.getKind() + ", total " + o.getTotal().toPlainString() + ", to pay " + o.getPayable().toPlainString();
    }

    static QuoteView quoteView(Quote q, Instant validUntil) {
        TermSegment after = q.target().getSegmentAfter();
        PlanTerms terms = after.getTerms();
        return new QuoteView(q.kind(), q.lines(), q.extraDiscountPercent(), q.taxable(), q.tax(), q.total(), q.credits(),
                q.creditFromNotes(), q.accountCredit(), q.accountCreditUsed(), q.payable(), after.getPlanCode(),
                terms.planName(), terms.period(), after.getTermStart(), after.getTermEnd(), validUntil);
    }

    /** @param names a cache of user and customer names by id, for one answer */
    OrderView view(BillingOrder o, Map<String, String> names) {
        TermSegment after = o.getTarget() == null ? null : o.getTarget().getSegmentAfter();
        OrderPayment p = o.getPayment();
        PaymentView payment = p == null ? null : new PaymentView(p.method(), p.reference(), p.receivedOn(), p.note(),
                userName(p.recordedBy(), names), p.recordedAt());
        String invoiceNumber = null;
        if (o.getInvoiceId() != null && o.getSteps().contains(BillingOrder.STEP_INVOICED)) {
            invoiceNumber = invoices.findById(o.getInvoiceId()).map(Invoice::getNumber).orElse(null);
        }
        boolean issued = o.getSteps().contains(BillingOrder.STEP_CREDIT_NOTED);
        return new OrderView(o.getId(), o.getNumber(), o.getCustomerId(),
                names.computeIfAbsent("c:" + o.getCustomerId(),
                        k -> customers.findById(o.getCustomerId()).map(Customer::getName).orElse("Deleted customer")),
                effective(o, clock.instant()), o.getRaisedBy(), userName(o.getRaisedBy(), names), o.isRaisedByStaff(),
                o.getRaisedAt(), o.getReason(), o.getExtraDiscountReason(), payment,
                invoiceNumber == null ? null : o.getInvoiceId(), invoiceNumber,
                issued ? o.getCredits().stream().map(OrderCredit::creditNoteId).toList() : List.of(),
                o.getRejectedReason(), o.getKind(), o.getLines(), o.getExtraDiscountPercent(), o.getTaxable(), o.getTax(),
                o.getTotal(), o.getCredits(), o.getCreditFromNotes(), o.getAccountCredit(), o.getAccountCreditUsed(),
                o.getPayable(), o.getPlanCode(), o.getPlanName(),
                after == null || after.getTerms() == null ? null : after.getTerms().period(),
                after == null ? null : after.getTermStart(), after == null ? null : after.getTermEnd(), o.getValidUntil(),
                o.getTarget() == null ? null : o.getTarget().getCreditPosting(),
                o.getTarget() == null ? null : o.getTarget().getNewTermEnd());
    }

    private String userName(String userId, Map<String, String> names) {
        if (userId == null || "system".equals(userId)) return "System";
        return names.computeIfAbsent("u:" + userId, k -> users.findById(userId).map(User::getName).orElse("Unknown user"));
    }
}
