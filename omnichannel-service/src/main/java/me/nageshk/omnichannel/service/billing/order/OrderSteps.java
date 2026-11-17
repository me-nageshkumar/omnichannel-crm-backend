package me.nageshk.omnichannel.service.billing.order;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import me.nageshk.omnichannel.common.billing.OrderKind;
import me.nageshk.omnichannel.common.billing.OrderStatus;
import me.nageshk.omnichannel.db.billing.BillingChange;
import me.nageshk.omnichannel.db.billing.BillingOrder;
import me.nageshk.omnichannel.db.billing.OrderTarget;
import me.nageshk.omnichannel.db.billing.Subscription;
import me.nageshk.omnichannel.db.billing.SubscriptionAddOn;
import me.nageshk.omnichannel.db.billing.SubscriptionRepository;
import me.nageshk.omnichannel.db.billing.TermSegment;
import me.nageshk.omnichannel.security.entitlement.Entitlements;
import me.nageshk.omnichannel.service.billing.BillingAudit;
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

    public OrderSteps(SubscriptionRepository subscriptions, Entitlements entitlements, BillingAudit audit,
            MongoTemplate mongo) {
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

    /** Issues the credit notes the order gives. Nothing yet: Task 10. */
    public void creditNotes(BillingOrder o) {}

    /** Issues the order's invoice. Nothing yet: Task 9. */
    public void invoice(BillingOrder o) {}

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
