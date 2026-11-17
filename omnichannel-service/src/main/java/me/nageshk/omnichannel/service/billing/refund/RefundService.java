package me.nageshk.omnichannel.service.billing.refund;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.nageshk.omnichannel.common.api.CodedException;
import me.nageshk.omnichannel.common.api.NotFoundException;
import me.nageshk.omnichannel.common.api.ValidationException;
import me.nageshk.omnichannel.db.billing.BillingChange;
import me.nageshk.omnichannel.db.billing.Refund;
import me.nageshk.omnichannel.db.billing.RefundRepository;
import me.nageshk.omnichannel.db.billing.RefundStatus;
import me.nageshk.omnichannel.db.billing.Subscription;
import me.nageshk.omnichannel.db.billing.SubscriptionRepository;
import me.nageshk.omnichannel.db.tenant.CustomerRepository;
import me.nageshk.omnichannel.db.user.User;
import me.nageshk.omnichannel.db.user.UserRepository;
import me.nageshk.omnichannel.security.entitlement.BillingSettingsStore;
import me.nageshk.omnichannel.service.billing.BillingAudit;
import me.nageshk.omnichannel.service.billing.money.Money;
import me.nageshk.omnichannel.service.billing.order.BillingLocks;
import me.nageshk.omnichannel.service.billing.order.BillingNumbers;
import me.nageshk.omnichannel.service.billing.order.OrderActivator;
import org.bson.types.Decimal128;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Refunds: account credit paid back to the customer outside the system and recorded here. The refund is stored
 * first, then the amount is taken off the balance by one update that only matches while the balance covers it and
 * has not been charged for this refund already. So a refund is never taken twice and the balance never goes below
 * zero, whatever is interrupted.
 */
@Service
public class RefundService {

    private static final Set<String> METHODS = Set.of("BANK_TRANSFER", "UPI", "CHEQUE", "CASH", "OTHER");

    public record RefundRequest(BigDecimal amount, String method, String reference, LocalDate paidOn, String note) {}

    public record RefundView(String id, String number, BigDecimal amount, String method, String reference,
            LocalDate paidOn, String note, String recordedByName, Instant recordedAt, RefundStatus status) {}

    private final RefundRepository refunds;
    private final SubscriptionRepository subscriptions;
    private final CustomerRepository customers;
    private final UserRepository users;
    private final BillingSettingsStore settings;
    private final BillingLocks locks;
    private final BillingNumbers numbers;
    private final OrderActivator activator;
    private final BillingAudit audit;
    private final MongoTemplate mongo;
    private final Clock clock;

    public RefundService(RefundRepository refunds, SubscriptionRepository subscriptions, CustomerRepository customers,
            UserRepository users, BillingSettingsStore settings, BillingLocks locks, BillingNumbers numbers,
            OrderActivator activator, BillingAudit audit, MongoTemplate mongo, Clock clock) {
        this.refunds = refunds;
        this.subscriptions = subscriptions;
        this.customers = customers;
        this.users = users;
        this.settings = settings;
        this.locks = locks;
        this.numbers = numbers;
        this.activator = activator;
        this.audit = audit;
        this.mongo = mongo;
        this.clock = clock;
    }

    /**
     * Records a refund that was paid.
     *
     * @throws CodedException 409 NOT_ENOUGH_CREDIT when the customer holds less account credit than the amount
     */
    public RefundView record(String customerId, RefundRequest req, String by) {
        requireCustomer(customerId);
        Instant now = clock.instant();
        Map<String, String> errors = new LinkedHashMap<>();
        BigDecimal amount = req == null ? null : req.amount();
        String method = req == null || req.method() == null ? "" : req.method().trim();
        String reference = req == null || req.reference() == null ? "" : req.reference().trim();
        String note = req == null || req.note() == null ? "" : req.note().trim();
        LocalDate paidOn = req == null ? null : req.paidOn();
        if (amount == null || amount.signum() <= 0) errors.put("amount", "must be more than 0");
        else if (amount.stripTrailingZeros().scale() > 2) errors.put("amount", "at most 2 decimals");
        if (!METHODS.contains(method)) errors.put("method", "must be one of BANK_TRANSFER, UPI, CHEQUE, CASH, OTHER");
        if (reference.isEmpty()) errors.put("reference", "is required");
        else if (reference.length() > 100) errors.put("reference", "at most 100 characters");
        if (paidOn == null) errors.put("paidOn", "is required");
        else if (paidOn.isAfter(now.atZone(settings.zone()).toLocalDate())) errors.put("paidOn", "cannot be in the future");
        else if (paidOn.isBefore(now.atZone(settings.zone()).toLocalDate().minusDays(366))) errors.put("paidOn", "cannot be more than a year ago");
        if (note.length() > 300) errors.put("note", "at most 300 characters");
        if (!errors.isEmpty()) throw new ValidationException(errors);

        return locks.withCustomer(customerId, () -> {
            activator.finishPending(customerId);
            finishPending(customerId);
            Refund r = new Refund();
            r.setId(new ObjectId().toHexString());
            r.setNumber(numbers.refund());
            r.setCustomerId(customerId);
            r.setAmount(Money.round(amount));
            r.setMethod(method);
            r.setReference(reference);
            r.setPaidOn(paidOn);
            r.setNote(note.isEmpty() ? null : note);
            r.setRecordedBy(by);
            r.setRecordedAt(now);
            r.setStatus(RefundStatus.RECORDING);
            refunds.insert(r);
            if (!complete(r)) {
                throw new CodedException(HttpStatus.CONFLICT, "NOT_ENOUGH_CREDIT", null,
                        "The customer holds less account credit than " + r.getAmount().toPlainString());
            }
            return view(r, new HashMap<>());
        });
    }

    /** Completes the refunds of the customer that were stored and not finished. */
    public void finishPending(String customerId) {
        locks.withCustomer(customerId, () -> {
            mongo.find(new Query(Criteria.where("customerId").is(customerId).and("status").is(RefundStatus.RECORDING)),
                    Refund.class).forEach(this::complete);
            return null;
        });
    }

    /** Takes the amount off the balance unless that was done; false when the balance does not cover it. */
    private boolean complete(Refund r) {
        mongo.updateFirst(
                new Query(Criteria.where("customerId").is(r.getCustomerId())
                        .and("creditBalance").gte(new Decimal128(r.getAmount())).and("refundIds").ne(r.getId())),
                new Update().inc("creditBalance", new Decimal128(r.getAmount().negate())).addToSet("refundIds", r.getId())
                        .inc("version", 1),
                Subscription.class);
        boolean taken = subscriptions.findByCustomerId(r.getCustomerId())
                .map(s -> s.getRefundIds().contains(r.getId())).orElse(false);
        r.setStatus(taken ? RefundStatus.RECORDED : RefundStatus.FAILED);
        mongo.updateFirst(new Query(Criteria.where("_id").is(r.getId())), new Update().set("status", r.getStatus()),
                Refund.class);
        if (taken) {
            audit.recordOnce("refund:" + r.getId(), BillingChange.SUBSCRIPTION, r.getCustomerId(), "REFUND",
                    "Refund " + r.getNumber() + " of " + r.getAmount().toPlainString() + " by " + r.getMethod() + " ("
                            + r.getReference() + ")",
                    r.getRecordedBy());
        }
        return taken;
    }

    /** Newest first, with the attempts that failed. */
    public List<RefundView> list(String customerId) {
        requireCustomer(customerId);
        Map<String, String> names = new HashMap<>();
        return refunds.findByCustomerIdOrderByRecordedAtDescIdDesc(customerId).stream().map(r -> view(r, names)).toList();
    }

    /** What the customer sees: the refunds that were made. */
    public List<RefundView> listRecorded(String customerId) {
        return list(customerId).stream().filter(r -> r.status() == RefundStatus.RECORDED)
                .map(r -> new RefundView(r.id(), r.number(), r.amount(), r.method(), r.reference(), r.paidOn(), null, null,
                        r.recordedAt(), r.status()))
                .toList();
    }

    private void requireCustomer(String customerId) {
        if (!customers.existsById(customerId)) throw new NotFoundException("customer not found");
    }

    private RefundView view(Refund r, Map<String, String> names) {
        return new RefundView(r.getId(), r.getNumber(), r.getAmount(), r.getMethod(), r.getReference(), r.getPaidOn(),
                r.getNote(), names.computeIfAbsent(r.getRecordedBy() == null ? "" : r.getRecordedBy(),
                        id -> id.isEmpty() ? "System" : users.findById(id).map(User::getName).orElse("Unknown user")),
                r.getRecordedAt(), r.getStatus());
    }
}
