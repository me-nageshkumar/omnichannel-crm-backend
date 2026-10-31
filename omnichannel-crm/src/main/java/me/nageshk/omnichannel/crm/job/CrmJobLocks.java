package me.nageshk.omnichannel.crm.job;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/**
 * Runs a CRM job on one instance at a time, on a lease in {@code crmJobLocks} (see {@link CrmJobLock}). Taking the
 * lease is one atomic upsert on the job's {@code _id}: it matches only a free lease, and when the lease is held it
 * tries an insert that the unique {@code _id} refuses, so of any number of instances exactly one wins. The lease
 * expires by itself, so a crashed holder blocks the job for at most its length; instances compare their own clocks,
 * which is fine with leases of minutes. These are global jobs, the one query here that carries no workspaceId.
 */
@Component
public class CrmJobLocks {

    private static final Logger log = LoggerFactory.getLogger(CrmJobLocks.class);

    /** A finished run keeps the lease this long, so instances whose cron ticks land moments apart do not both run. */
    static final Duration MIN_HOLD = Duration.ofSeconds(30);

    private final MongoTemplate mongo;
    private final Clock clock;

    CrmJobLocks(MongoTemplate mongo, Clock clock) {
        this.mongo = mongo;
        this.clock = clock;
    }

    /**
     * Runs {@code work} if the job's lease is free, holding it for at most {@code lease}, which must outlast a normal
     * run. The lease goes back when the work ends, also when it fails; failing to give it back only logs, because
     * the lease expires by itself and the job's own failure is the one that matters.
     *
     * @return false when another run holds the lease and nothing ran
     */
    public boolean runExclusively(String job, Duration lease, Runnable work) {
        Instant start = Instant.now(clock);
        String holder = UUID.randomUUID().toString();
        if (!take(job, holder, start, lease)) return false;
        try {
            work.run();
        } finally {
            try {
                giveBack(job, holder, start);
            } catch (RuntimeException e) {
                log.warn("could not give back the {} lease; it expires by itself", job, e);
            }
        }
        return true;
    }

    private boolean take(String job, String holder, Instant now, Duration lease) {
        Query free = new Query(Criteria.where("_id").is(job).and("lockedUntil").lte(now));
        try {
            mongo.upsert(free, new Update().set("holder", holder).set("lockedUntil", now.plus(lease)), CrmJobLock.class);
            return true;
        } catch (DuplicateKeyException held) {
            return false;
        }
    }

    /** Matches the run's own token: one that outlived its lease leaves the next holder's alone. */
    private void giveBack(String job, String holder, Instant start) {
        Instant held = start.plus(MIN_HOLD);
        Instant now = Instant.now(clock);
        mongo.updateFirst(new Query(Criteria.where("_id").is(job).and("holder").is(holder)),
                new Update().set("lockedUntil", held.isAfter(now) ? held : now), CrmJobLock.class);
    }
}
