package me.nageshk.omnichannel.crm.job;

import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import me.nageshk.omnichannel.crm.segment.SegmentCountRefreshJob;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the CRM's scheduled jobs, each on one instance at a time ({@link CrmJobLocks}): every instance ticks and the
 * one that takes the lease does the work. A crash blocks a job for at most its lease. Switched off with
 * {@code omnichannel.crm.jobs.enabled=false} (tests), in which case nothing is scheduled and the jobs only run when
 * called, without a lock.
 */
@Component
@ConditionalOnProperty(name = "omnichannel.crm.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class CrmJobsTicker {

    static final String REMINDERS = "taskReminders";
    static final String ROTTING = "dealRotting";
    static final String SEGMENT_COUNTS = "segmentCounts";
    /** A run claims at most {@value TaskReminderJob#BATCH} reminders, seconds of work. */
    static final Duration REMINDERS_LEASE = Duration.ofMinutes(5);
    /** Updates per pipeline of every brand, once a night. */
    static final Duration ROTTING_LEASE = Duration.ofHours(2);
    /** Past the hourly tick, so a slow recount keeps the next one out; every segment of every brand, 10 s per query. */
    static final Duration SEGMENT_COUNTS_LEASE = Duration.ofHours(2);

    private final TaskReminderJob reminders;
    private final DealRottingJob rotting;
    private final SegmentCountRefreshJob segmentCounts;
    private final CrmJobLocks locks;

    CrmJobsTicker(TaskReminderJob reminders, DealRottingJob rotting, SegmentCountRefreshJob segmentCounts,
            CrmJobLocks locks) {
        this.reminders = reminders;
        this.rotting = rotting;
        this.segmentCounts = segmentCounts;
        this.locks = locks;
    }

    @Scheduled(fixedDelay = 60_000)
    void reminders() {
        locks.runExclusively(REMINDERS, REMINDERS_LEASE, reminders::run);
    }

    @Scheduled(cron = "0 15 2 * * *")
    void rotting() {
        locks.runExclusively(ROTTING, ROTTING_LEASE, rotting::run);
    }

    @Scheduled(cron = "0 5 * * * *")
    void segmentCounts() {
        locks.runExclusively(SEGMENT_COUNTS, SEGMENT_COUNTS_LEASE, segmentCounts::run);
    }
}
