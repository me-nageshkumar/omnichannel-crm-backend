package me.nageshk.omnichannel.app.crm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import me.nageshk.omnichannel.crm.job.CrmJobLock;
import me.nageshk.omnichannel.crm.job.CrmJobLocks;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

/** The lease that keeps a CRM job on one instance at a time; two callers here stand for two instances. */
class CrmJobLocksIT extends CrmTestBase {

    static final String JOB = "someJob";
    static final Duration LEASE = Duration.ofMinutes(10);

    @Autowired CrmJobLocks locks;

    private Document stored() {
        return mongo.findOne(new Query(Criteria.where("_id").is(JOB)), Document.class, "crmJobLocks");
    }

    private void storeLease(String holder, Instant until) {
        CrmJobLock lock = new CrmJobLock();
        lock.setJob(JOB);
        lock.setHolder(holder);
        lock.setLockedUntil(until);
        mongo.save(lock);
    }

    private boolean run(AtomicInteger runs) {
        return locks.runExclusively(JOB, LEASE, runs::incrementAndGet);
    }

    @Test
    void aSecondInstanceDoesNotRunWhileTheFirstHoldsTheLease() {
        AtomicInteger inner = new AtomicInteger();
        boolean[] secondRan = new boolean[1];
        boolean first = locks.runExclusively(JOB, LEASE, () -> {
            assertThat(stored().getDate("lockedUntil").toInstant())
                    .isAfter(Instant.now().plus(LEASE).minus(Duration.ofMinutes(1)));
            secondRan[0] = run(inner);
        });
        assertThat(first).isTrue();
        assertThat(secondRan[0]).isFalse();
        assertThat(inner).hasValue(0);
    }

    @Test
    void onlyOneOfManySimultaneousInstancesRuns() throws Exception {
        for (boolean expired : new boolean[] {false, true}) {
            mongo.remove(new Query(), "crmJobLocks");
            if (expired) storeLease("crashed", Instant.now().minusSeconds(60));
            AtomicInteger runs = new AtomicInteger();
            ExecutorService pool = Executors.newFixedThreadPool(8);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Boolean>> calls = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                calls.add(pool.submit(() -> {
                    start.await();
                    return run(runs);
                }));
            }
            start.countDown();
            int won = 0;
            for (Future<Boolean> call : calls) won += call.get() ? 1 : 0;
            pool.shutdownNow();
            assertThat(won).as(expired ? "over an expired lease" : "with no lease yet").isEqualTo(1);
            assertThat(runs).hasValue(1);
        }
    }

    @Test
    void aCrashedHolderBlocksOnlyUntilItsLeaseEnds() {
        AtomicInteger runs = new AtomicInteger();
        storeLease("crashed", Instant.now().plusSeconds(60));
        assertThat(run(runs)).isFalse();
        storeLease("crashed", Instant.now().minusSeconds(1));
        assertThat(run(runs)).isTrue();
        assertThat(runs).hasValue(1);
    }

    @Test
    void aFinishedRunFreesTheLeaseAfterTheShortHold() {
        AtomicInteger runs = new AtomicInteger();
        Instant before = Instant.now();
        assertThat(run(runs)).isTrue();
        Instant freeAt = stored().getDate("lockedUntil").toInstant();
        assertThat(freeAt).isBefore(before.plus(LEASE).minus(Duration.ofMinutes(1)));
        // another instance whose same cron tick lands a moment later does not repeat the run
        assertThat(freeAt).isAfter(before.plusSeconds(20));
        assertThat(run(runs)).isFalse();
        assertThat(runs).hasValue(1);
    }

    @Test
    void aFailingJobStillGivesTheLeaseBack() {
        Instant before = Instant.now();
        assertThatThrownBy(() -> locks.runExclusively(JOB, LEASE, () -> {
            throw new IllegalStateException("boom");
        })).hasMessage("boom");
        assertThat(stored().getDate("lockedUntil").toInstant()).isBefore(before.plus(LEASE).minus(Duration.ofMinutes(1)));
    }

    @Test
    void aRunThatOutlivedItsLeaseLeavesTheNextHolderAlone() {
        Instant theirs = Instant.now().plus(LEASE).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        locks.runExclusively(JOB, LEASE, () -> storeLease("next", theirs));
        assertThat(stored().getString("holder")).isEqualTo("next");
        assertThat(stored().getDate("lockedUntil").toInstant()).isEqualTo(theirs);
    }

    @Test
    void eachJobHasItsOwnLease() {
        storeLease("other", Instant.now().plusSeconds(60));
        AtomicInteger runs = new AtomicInteger();
        assertThat(locks.runExclusively("anotherJob", LEASE, runs::incrementAndGet)).isTrue();
        assertThat(runs).hasValue(1);
    }
}
