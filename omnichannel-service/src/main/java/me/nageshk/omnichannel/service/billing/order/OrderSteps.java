package me.nageshk.omnichannel.service.billing.order;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import me.nageshk.omnichannel.common.billing.OrderKind;
import me.nageshk.omnichannel.common.billing.OrderStatus;
import me.nageshk.omnichannel.common.billing.WalletEntryType;
import me.nageshk.omnichannel.db.billing.BillingChange;
import me.nageshk.omnichannel.db.billing.BillingOrder;
import me.nageshk.omnichannel.db.billing.OrderCredit;
import me.nageshk.omnichannel.db.billing.OrderTarget;
import me.nageshk.omnichannel.db.billing.Subscription;
import me.nageshk.omnichannel.db.billing.SubscriptionAddOn;
import me.nageshk.omnichannel.db.billing.SubscriptionRepository;
import me.nageshk.omnichannel.db.billing.TermSegment;
import me.nageshk.omnichannel.db.billing.WalletEntry;
import me.nageshk.omnichannel.security.entitlement.Entitlements;
import me.nageshk.omnichannel.service.billing.BillingAudit;
import me.nageshk.omnichannel.service.billing.document.CreditNoteService;
import me.nageshk.omnichannel.service.billing.document.InvoiceService;
import me.nageshk.omnichannel.service.billing.money.Money;
import me.nageshk.omnichannel.service.billing.wallet.WalletService;
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
    private final Clock clock;
    private final WalletService wallets;

    public OrderSteps(SubscriptionRepository subscriptions, Entitlements entitlements, BillingAudit audit,
            MongoTemplate mongo, InvoiceService invoices, CreditNoteService creditNotes, Clock clock,
            WalletService wallets) {
        this.wallets = wallets;
        this.clock = clock;
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
        if (o.getKind() == OrderKind.TOPUP || o.getKind() == OrderKind.WALLET_REFUND) {
            // wallet money: the plan is not touched and no request beside it goes out of date
            audit.recordOnce("order:" + o.getId(), BillingChange.SUBSCRIPTION, customerId, "ORDER_ACTIVATED",
                    "Order " + o.getNumber() + " activated: " + describe(o),
                    o.getPayment() == null ? "system" : o.getPayment().recordedBy());
            return;
        }
        Subscription sub = subscriptions.findByCustomerId(customerId).orElse(null);
        if (sub == null || !sub.getAppliedOrderIds().contains(o.getId())) {
            if (sub == null) {
                sub = new Subscription();
                sub.setCustomerId(customerId);
            }
            OrderTarget target = o.getTarget();
            boolean plain = true;
            if (o.getKind() == OrderKind.ADDON) {
                Instant now = clock.instant();
                addTo(sub, target.getSegmentAfter(), o, now);
                if (target.getNextAfter() != null) addTo(sub, target.getNextAfter(), o, now);
            } else if (o.getKind() == OrderKind.CANCEL_ADDON) {
                String gone = target.getRemoveAddOnId();
                sub.getCurrent().getAddOns().removeIf(a -> a.getId().equals(gone));
                if (sub.getNext() != null) sub.getNext().getAddOns().removeIf(a -> a.getId().equals(gone));
            } else if (o.getKind() == OrderKind.CANCEL_PLAN) {
                sub.setCurrent(target.getSegmentAfter());
                sub.setNext(null);
                sub.setCancelled(true);
            } else if (o.getKind() == OrderKind.ADJUST) {
                if (target.getNewTermEnd() != null) {
                    (sub.getNext() != null ? sub.getNext() : sub.getCurrent()).setTermEnd(target.getNewTermEnd());
                    if (target.getAnchorDay() != null) sub.setAnchorDay(target.getAnchorDay());
                    sub.setTermRevision(sub.getTermRevision() + 1);
                } else {
                    // account credit is not part of the plan: requests that wait stay good
                    sub.setCreditBalance(Money.round(Money.zeroIfNull(sub.getCreditBalance()).add(target.getCreditPosting())));
                    plain = false;
                }
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
            if (plain) sub.setRevision(sub.getRevision() + 1);
            if (o.getKind().changesTerm()) sub.setTermRevision(sub.getTermRevision() + 1);
            sub.getAppliedOrderIds().add(o.getId());
            subscriptions.save(sub);
        }
        entitlements.evict(customerId);
        audit.recordOnce("order:" + o.getId(), BillingChange.SUBSCRIPTION, customerId, "ORDER_ACTIVATED",
                "Order " + o.getNumber() + " activated: " + describe(o),
                o.getPayment() == null ? "system" : o.getPayment().recordedBy());
        supersede(o, sub.getRevision());
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
     * Moves the wallet for an order that is about wallet money: a top-up puts its amount (before tax) in. The wallet
     * remembers the orders it has seen, so this happens once however often it is run.
     */
    public void wallet(BillingOrder o) {
        if (o.getKind() != OrderKind.TOPUP) return;
        WalletEntry draft = new WalletEntry();
        draft.setType(WalletEntryType.TOPUP);
        draft.setRef(o.getNumber());
        draft.setBy(o.getPayment() == null ? null : o.getPayment().recordedBy());
        wallets.creditOnce(o.getId(), o.getCustomerId(), o.getTaxable(), draft);
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
    private static void addTo(Subscription sub, TermSegment priced, BillingOrder o, Instant now) {
        TermSegment stored = sub.getNext() != null && sub.getNext().getTermStart().equals(priced.getTermStart())
                ? sub.getNext()
                : sub.getCurrent() != null && sub.getCurrent().getTermStart().equals(priced.getTermStart())
                        ? sub.getCurrent() : null;
        if (stored == null) {
            // the term ended while the activation was interrupted: there is nothing left of it to put the add-on on
            if (!now.isBefore(priced.getTermEnd())) return;
            throw new IllegalStateException("order " + o.getNumber() + ": the term it was priced for is gone");
        }
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
    private void supersede(BillingOrder o, long revisionNow) {
        // only what was priced before this change: a request raised since was priced on the result
        Criteria others = Criteria.where("customerId").is(o.getCustomerId()).and("status").is(OrderStatus.REQUESTED)
                .and("quotedRevision").lt(revisionNow)
                .and("_id").ne(new org.bson.types.ObjectId(o.getId()));
        if (o.getKind() == OrderKind.ADJUST && o.getTarget().getNewTermEnd() == null) return;
        // a top-up is money into the wallet: no change of plan makes it stale
        others = o.getKind() == OrderKind.ADDON ? others.and("kind").in(STALE_AFTER_ADDON)
                : others.and("kind").ne(OrderKind.TOPUP);
        mongo.updateMulti(new Query(others), new Update().set("status", OrderStatus.SUPERSEDED).inc("version", 1),
                BillingOrder.class);
    }

    private static String describe(BillingOrder o) {
        if (o.getLines().isEmpty()) {
            OrderTarget t = o.getTarget();
            String what = switch (o.getKind()) {
                case CANCEL_ADDON -> "add-on cancelled";
                case CANCEL_PLAN -> "plan cancelled";
                case ADJUST -> t.getNewTermEnd() != null ? "end of the term moved"
                        : "account credit " + (t.getCreditPosting().signum() > 0 ? "+" : "")
                                + Money.round(t.getCreditPosting()).toPlainString();
                default -> o.getKind().name();
            };
            if (o.getCreditFromNotes().signum() > 0) what += ", credit " + o.getCreditFromNotes().toPlainString();
            return o.getReason() == null ? what : what + " (" + o.getReason() + ")";
        }
        String what = o.getLines().get(0).description();
        if (o.getLines().size() > 1) what += " and " + (o.getLines().size() - 1) + " more";
        return what + ", total " + o.getTotal().toPlainString();
    }
}
