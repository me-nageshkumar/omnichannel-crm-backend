package me.nageshk.omnichannel.service.billing.order;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import me.nageshk.omnichannel.common.api.CodedException;
import me.nageshk.omnichannel.db.billing.BillingLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Makes billing changes to one customer happen one after another, on any number of servers: whoever changes a
 * customer's orders or subscription holds that customer's lease while doing it. The lease runs out by itself, so a
 * server that dies holding one only delays the next change.
 */
@Component
public class BillingLocks {

    private static final Logger log = LoggerFactory.getLogger(BillingLocks.class);

    static final Duration LEASE = Duration.ofSeconds(30);
    static final Duration WAIT = Duration.ofSeconds(5);
    private static final long POLL_MS = 100;

    private final MongoTemplate mongo;
    private final Clock clock;
    /** The customers this thread already holds, so work that needs the lease again does not wait for itself. */
    private final ThreadLocal<Set<String>> held = ThreadLocal.withInitial(HashSet::new);

    public BillingLocks(MongoTemplate mongo, Clock clock) {
        this.mongo = mongo;
        this.clock = clock;
    }

    /**
     * Runs the work while holding the customer's lease, waiting a few seconds for a holder to finish.
     *
     * @throws CodedException 409 CONFLICT when the lease is not free in time
     */
    public <T> T withCustomer(String customerId, Supplier<T> work) {
        if (held.get().contains(customerId)) return work.get();
        String holder = UUID.randomUUID().toString();
        if (!take(customerId, holder)) {
            throw new CodedException(HttpStatus.CONFLICT, "CONFLICT", null,
                    "Another billing change for this customer is in progress; try again");
        }
        held.get().add(customerId);
        try {
            return work.get();
        } finally {
            held.get().remove(customerId);
            try {
                mongo.updateFirst(new Query(Criteria.where("_id").is(customerId).and("holder").is(holder)),
                        new Update().set("lockedUntil", clock.instant()), BillingLock.class);
            } catch (RuntimeException e) {
                log.warn("could not give back the billing lease of {}; it runs out by itself", customerId, e);
            }
        }
    }

    /** As {@link #withCustomer}, but gives up at once when another holder has the lease: for the ticker. */
    public boolean tryWithCustomer(String customerId, Runnable work) {
        if (held.get().contains(customerId)) {
            work.run();
            return true;
        }
        String holder = UUID.randomUUID().toString();
        if (!takeOnce(customerId, holder)) return false;
        held.get().add(customerId);
        try {
            work.run();
        } finally {
            held.get().remove(customerId);
            mongo.updateFirst(new Query(Criteria.where("_id").is(customerId).and("holder").is(holder)),
                    new Update().set("lockedUntil", clock.instant()), BillingLock.class);
        }
        return true;
    }

    private boolean take(String customerId, String holder) {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (true) {
            if (takeOnce(customerId, holder)) return true;
            if (System.nanoTime() >= deadline) return false;
            try {
                Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    private boolean takeOnce(String customerId, String holder) {
        Instant now = clock.instant();
        Query free = new Query(Criteria.where("_id").is(customerId).and("lockedUntil").lte(now));
        try {
            mongo.upsert(free, new Update().set("holder", holder).set("lockedUntil", now.plus(LEASE)), BillingLock.class);
            return true;
        } catch (DuplicateKeyException heldByAnother) {
            return false;
        }
    }
}
