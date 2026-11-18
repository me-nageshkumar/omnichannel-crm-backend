package me.nageshk.omnichannel.service.billing;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import me.nageshk.omnichannel.common.billing.OrderStatus;
import me.nageshk.omnichannel.db.billing.BillingOrder;
import me.nageshk.omnichannel.db.billing.Refund;
import me.nageshk.omnichannel.db.billing.RefundStatus;
import me.nageshk.omnichannel.db.billing.Subscription;
import me.nageshk.omnichannel.service.billing.order.BillingLocks;
import me.nageshk.omnichannel.service.billing.order.OrderActivator;
import me.nageshk.omnichannel.service.billing.refund.RefundService;
import org.bson.Document;
import org.bson.types.ObjectId;
import me.nageshk.omnichannel.db.billing.Wallet;
import me.nageshk.omnichannel.service.billing.wallet.WalletMover;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/**
 * The background pass over billing. Nothing a customer is entitled to depends on it (the state of a subscription
 * is worked out from its dates on every read); it finishes what a stopped server left half done and keeps the
 * stored documents tidy.
 */
@Component
public class BillingTicker {

    private static final Logger log = LoggerFactory.getLogger(BillingTicker.class);
    /** How long an activation or refund may be under way before it is taken to be abandoned. */
    static final Duration STUCK_AFTER = Duration.ofSeconds(60);

    private final MongoTemplate mongo;
    private final BillingLocks locks;
    private final OrderActivator activator;
    private final RefundService refunds;
    private final WalletMover wallets;
    private final Clock clock;

    public BillingTicker(MongoTemplate mongo, BillingLocks locks, OrderActivator activator, RefundService refunds,
            WalletMover wallets, Clock clock) {
        this.wallets = wallets;
        this.mongo = mongo;
        this.locks = locks;
        this.activator = activator;
        this.refunds = refunds;
        this.clock = clock;
    }

    public void tick() {
        Instant now = clock.instant();
        finishAbandoned(now);
        expireOldRequests(now);
        rollRenewalsIn(now);
        moveWalletEntries(now);
    }

    /**
     * Ledger entries that have waited on their wallet for more than a minute: the server that made them stopped, or
     * could not reach the database, before copying them out.
     */
    private void moveWalletEntries(Instant now) {
        Query waiting = new Query(Criteria.where("pending.at").lt(now.minus(STUCK_AFTER)));
        waiting.fields().include("customerId");
        mongo.find(waiting, Wallet.class).forEach(w -> wallets.moveQuietly(w.getCustomerId()));
    }

    /** Activations and refunds that were started more than a minute ago and never completed. */
    private void finishAbandoned(Instant now) {
        Instant before = now.minus(STUCK_AFTER);
        Set<String> customerIds = new LinkedHashSet<>();
        mongo.find(new Query(Criteria.where("status").is(OrderStatus.ACTIVATING).and("activatingSince").lt(before)),
                BillingOrder.class).forEach(o -> customerIds.add(o.getCustomerId()));
        mongo.find(new Query(Criteria.where("status").is(RefundStatus.RECORDING).and("recordedAt").lt(before)),
                Refund.class).forEach(r -> customerIds.add(r.getCustomerId()));
        for (String customerId : customerIds) {
            try {
                // a customer someone is changing right now is left for the next tick
                locks.tryWithCustomer(customerId, () -> {
                    activator.finishPending(customerId);
                    refunds.finishPending(customerId);
                });
            } catch (RuntimeException e) {
                log.warn("could not finish the interrupted billing work of customer {}", customerId, e);
            }
        }
    }

    /** A request past its validity already reads as expired; this stores it. */
    private void expireOldRequests(Instant now) {
        mongo.updateMulti(new Query(Criteria.where("status").is(OrderStatus.REQUESTED).and("validUntil").lte(now)),
                new Update().set("status", OrderStatus.EXPIRED).inc("version", 1), BillingOrder.class);
    }

    /**
     * A renewal paid early is in force from its own start whatever is stored; here it takes the place of the term
     * it followed. The update only matches the renewal that was read, so two servers do it once.
     */
    private void rollRenewalsIn(Instant now) {
        List<Subscription> due = mongo.find(new Query(Criteria.where("next.termStart").lte(now)), Subscription.class);
        for (Subscription s : due) {
            try {
                mongo.getCollection(mongo.getCollectionName(Subscription.class)).updateOne(
                        new Document("_id", new ObjectId(s.getId())).append("next.termStart", Date.from(s.getNext().getTermStart())),
                        List.of(new Document("$set", new Document("current", "$next")
                                        .append("version", new Document("$add", List.of(new Document("$ifNull", List.of("$version", 0L)), 1L)))),
                                new Document("$unset", "next")));
            } catch (RuntimeException e) {
                log.warn("could not move the renewal of customer {} into place", s.getCustomerId(), e);
            }
        }
    }
}
