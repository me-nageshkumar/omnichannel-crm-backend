package me.nageshk.omnichannel.crm.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import me.nageshk.omnichannel.crm.segment.SegmentCountRefreshJob;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class CrmJobsTickerTest {

    final TaskReminderJob reminders = mock(TaskReminderJob.class);
    final DealRottingJob rotting = mock(DealRottingJob.class);
    final SegmentCountRefreshJob segmentCounts = mock(SegmentCountRefreshJob.class);
    final CrmJobLocks locks = mock(CrmJobLocks.class);
    final CrmJobsTicker ticker = new CrmJobsTicker(reminders, rotting, segmentCounts, locks);

    @Test
    void aJobWhoseLockIsHeldElsewhereDoesNotRun() {
        when(locks.runExclusively(any(), any(), any())).thenReturn(false);
        ticker.reminders();
        ticker.rotting();
        ticker.segmentCounts();
        verify(reminders, never()).run();
        verify(rotting, never()).run();
        verify(segmentCounts, never()).run();
    }

    @Test
    void eachJobRunsUnderItsOwnLock() {
        Map<String, Duration> leases = new HashMap<>();
        List<Runnable> works = new ArrayList<>();
        when(locks.runExclusively(any(), any(), any())).thenAnswer(call -> {
            leases.put(call.getArgument(0), call.getArgument(1));
            works.add(call.getArgument(2));
            return true;
        });
        ticker.reminders();
        ticker.rotting();
        ticker.segmentCounts();
        assertThat(leases).hasSize(3);
        works.forEach(Runnable::run);
        verify(reminders).run();
        verify(rotting).run();
        verify(segmentCounts).run();
    }

    @Test
    void leasesOutlastARun() {
        ticker.reminders();
        verify(locks).runExclusively(eq(CrmJobsTicker.REMINDERS), eq(CrmJobsTicker.REMINDERS_LEASE), any());
        assertThat(CrmJobsTicker.REMINDERS_LEASE).isGreaterThan(Duration.ofMinutes(1));
        // an hourly job's slow run must keep the next tick out
        assertThat(CrmJobsTicker.SEGMENT_COUNTS_LEASE).isGreaterThan(Duration.ofHours(1));
        assertThat(CrmJobsTicker.ROTTING_LEASE).isGreaterThan(Duration.ofHours(1));
    }

    private ApplicationContextRunner context() {
        return new ApplicationContextRunner().withUserConfiguration(CrmJobsTicker.class)
                .withBean(TaskReminderJob.class, () -> reminders).withBean(DealRottingJob.class, () -> rotting)
                .withBean(SegmentCountRefreshJob.class, () -> segmentCounts).withBean(CrmJobLocks.class, () -> locks);
    }

    @Test
    void theSwitchStillTurnsTheTickerOff() {
        context().run(c -> assertThat(c).hasSingleBean(CrmJobsTicker.class));
        context().withPropertyValues("omnichannel.crm.jobs.enabled=true")
                .run(c -> assertThat(c).hasSingleBean(CrmJobsTicker.class));
        context().withPropertyValues("omnichannel.crm.jobs.enabled=false")
                .run(c -> assertThat(c).doesNotHaveBean(CrmJobsTicker.class));
    }
}
