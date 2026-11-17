package me.nageshk.omnichannel.service.billing.order;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import me.nageshk.omnichannel.common.billing.OrderKind;
import me.nageshk.omnichannel.common.billing.OrderStatus;
import me.nageshk.omnichannel.db.billing.BillingChange;
import me.nageshk.omnichannel.db.billing.BillingOrder;
import me.nageshk.omnichannel.db.billing.OrderCredit;
import me.nageshk.omnichannel.db.billing.OrderTarget;
import me.nageshk.omnichannel.db.billing.Subscription;
import me.nageshk.omnichannel.db.billing.SubscriptionAddOn;
import me.nageshk.omnichannel.db.billing.SubscriptionRepository;
import me.nageshk.omnichannel.db.billing.TermSegment;
import me.nageshk.omnichannel.security.entitlement.Entitlements;
import me.nageshk.omnichannel.service.billing.BillingAudit;
import me.nageshk.omnichannel.service.billing.document.CreditNoteService;
import me.nageshk.omnichannel.service.billing.document.InvoiceService;
import org.bson.types.Decimal128;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/**
 * The things activating an order does, each written so that doing it a second time changes nothing: an activation
 * that stopped half way is finished by running its steps again. Called only by {@link OrderActivator}, under the
 * customer's lease.
 */
@Component
public class OrderSteps {

    private static final Set<OrderKind> STALE_AFTER_ADDON = EnumSet.of(OrderKind.RENEWAL, OrderKind.UPGRADE,
            OrderKind.CHANGE);

    private final SubscriptionRepository subscriptions;
    private final Entitlements entitlements;
    private final BillingAudit audit;
    private final MongoTemplate mongo;
    private final InvoiceService invoices;
    private final CreditNoteService creditNotes;

    public OrderSteps(SubscriptionRepository subscriptions, Entitlements entitlements, BillingAudit audit,
            MongoTemplate mongo, InvoiceService invoices, CreditNoteService creditNotes) {
        this.invoices = invoices;
        this.creditNotes = creditNotes;
        this.subscriptions = subscriptions;
        this.entitlements = entitlements;
        this.audit = audit;
        this.mongo = mongo;
    }

    /**
     * Puts the order's effect on the subscription. The subscription remembers the orders applied to it, and the
     * save is refused if anything else changed it meanwhile, so the effect lands once or not at all.
     */
    public void apply(BillingOrder o) {
        String customerId = o.getCustomerId();
        Subscription sub = subscriptions.findByCustomerId(customerId).orElse(null);
        if (sub == null || !sub.getAppliedOrderIds().contains(o.getId())) {
            if (sub == null) {
                sub = new Subscription();
                sub.setCustomerId(customerId);
            }
            OrderTarget target = o.getTarget();
            if (o.getKind() == OrderKind.ADDON) {
                addTo(sub, target.getSegmentAfter(), o);
                if (target.getNextAfter() != null) addTo(sub, target.getNextAfter(), o);
            } else {
                TermSegment segment = target.getSegmentAfter();
                // what the term was billed on, so that an upgrade later knows which invoice to credit
                segment.setPlanInvoiceId(o.getInvoiceId());
                for (SubscriptionAddOn a : segment.getAddOns()) {
                    a.setOrderId(o.getId());
                    a.setInvoiceId(o.getInvoiceId());
                }
                if (OrderTarget.NEXT.equals(target.getSegment())) {
                    sub.setNext(segment);
                } else {
                    sub.setCurrent(segment);
                    sub.setNext(null);
                }
                if (target.getAnchorDay() != null) sub.setAnchorDay(target.getAnchorDay());
                sub.setCancelled(false);
            }
            sub.setRevision(sub.getRevision() + 1);
            if (o.getKind().changesTerm()) sub.setTermRevision(sub.getTermRevision() + 1);
            sub.getAppliedOrderIds().add(o.getId());
            subscriptions.save(sub);
        }
        entitlements.evict(customerId);
        audit.recordOnce("order:" + o.getId(), BillingChange.SUBSCRIPTION, customerId, "ORDER_ACTIVATED",
                "Order " + o.getNumber() + " activated: " + describe(o),
                o.getPayment() == null ? "system" : o.getPayment().recordedBy());
        supersede(o);
    }

    /**
     * Issues the credit notes the order gives and adds their total to the customer's account credit. It runs
     * before the invoice, so the order's own invoice can draw on that credit. Each note is inserted under the id
     * the order reserved and the balance remembers the orders that added to it, so nothing here happens twice.
     */
    public void creditNotes(BillingOrder o) {
        if (o.getCredits().isEmpty()) return;
        for (OrderCredit credit : o.getCredits()) creditNotes.issue(o, credit);
        mongo.updateFirst(
                new Query(Criteria.where("customerId").is(o.getCustomerId()).and("creditedOrderIds").ne(o.getId())),
                new Update().inc("creditBalance", new Decimal128(o.getCreditFromNotes()))
                        .addToSet("creditedOrderIds", o.getId()).inc("version", 1),
                Subscription.class);
    }

    /**
     * Issues the order's invoice and takes the account credit it used off the customer's balance. An order with
     * nothing to charge has no invoice. The invoice is inserted under the id the order reserved and the balance
     * remembers the orders that drew on it, so running this again does neither a second time.
     */
    public void invoice(BillingOrder o) {
        if (o.getInvoiceId() == null || o.getTotal().signum() == 0) return;
        invoices.issue(o);
        if (o.getAccountCreditUsed().signum() > 0) {
            mongo.updateFirst(
                    new Query(Criteria.where("customerId").is(o.getCustomerId()).and("debitedOrderIds").ne(o.getId())),
                    new Update().inc("creditBalance", new Decimal128(o.getAccountCreditUsed().negate()))
                            .addToSet("debitedOrderIds", o.getId()).inc("version", 1),
                    Subscription.class);
        }
    }

    /** The add-on the order bought, which pricing put last in {@code priced}, goes onto the stored term of those dates. */
    private static void addTo(Subscription sub, TermSegment priced, BillingOrder o) {
        TermSegment stored = sub.getNext() != null && sub.getNext().getTermStart().equals(priced.getTermStart())
                ? sub.getNext()
                : sub.getCurrent() != null && sub.getCurrent().getTermStart().equals(priced.getTermStart())
                        ? sub.getCurrent() : null;
        if (stored == null) throw new IllegalStateException("order " + o.getNumber() + ": the term it was priced for is gone");
        List<SubscriptionAddOn> pricedAddOns = priced.getAddOns();
        SubscriptionAddOn bought = pricedAddOns.get(pricedAddOns.size() - 1);
        if (stored.getAddOns().stream().anyMatch(a -> a.getId().equals(bought.getId()))) return;
        bought.setOrderId(o.getId());
        bought.setInvoiceId(o.getInvoiceId());
        stored.getAddOns().add(bought);
    }

    /**
     * Requests priced on the subscription as it was are of no use now. A new term makes every other open request
     * stale; an add-on makes the open plan changes stale (their carried add-ons and credits no longer add up), but
     * not other add-on requests.
     */
    private void supersede(BillingOrder o) {
        Criteria others = Criteria.where("customerId").is(o.getCustomerId()).and("status").is(OrderStatus.REQUESTED)
                .and("_id").ne(new org.bson.types.ObjectId(o.getId()));
        if (!o.getKind().changesTerm()) others = others.and("kind").in(STALE_AFTER_ADDON);
        mongo.updateMulti(new Query(others), new Update().set("status", OrderStatus.SUPERSEDED).inc("version", 1),
                BillingOrder.class);
    }

    private static String describe(BillingOrder o) {
        String what = o.getLines().isEmpty() ? o.getKind().name() : o.getLines().get(0).description();
        if (o.getLines().size() > 1) what += " and " + (o.getLines().size() - 1) + " more";
        return what + ", total " + o.getTotal().toPlainString();
    }
}
