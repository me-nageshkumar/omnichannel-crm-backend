package me.nageshk.omnichannel.crm.job;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the CRM's scheduled jobs. Switched off with {@code omnichannel.crm.jobs.enabled=false} (tests, a second
 * instance), in which case nothing is scheduled and the jobs only run when called.
 */
@Component
@ConditionalOnProperty(name = "omnichannel.crm.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class CrmJobsTicker {

    private final TaskReminderJob reminders;
    private final DealRottingJob rotting;

    CrmJobsTicker(TaskReminderJob reminders, DealRottingJob rotting) {
        this.reminders = reminders;
        this.rotting = rotting;
    }

    @Scheduled(fixedDelay = 60_000)
    void reminders() {
        reminders.run();
    }

    @Scheduled(cron = "0 15 2 * * *")
    void rotting() {
        rotting.run();
    }
}
