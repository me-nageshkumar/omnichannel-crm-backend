package me.nageshk.omnichannel.service.inbox.history;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * The history import's own threads: its tick waits on devices page by page, so it must never hold up
 * {@code taskScheduler} (inbox pings, the media sweeper) or the campaign runner.
 */
@Configuration
class HistoryImportSchedulerConfig {

    @Bean
    ThreadPoolTaskScheduler historyImportScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("history-");
        scheduler.setDaemon(true);
        // a page in flight finishes (or times out) and is recorded before the application stops
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(75);
        return scheduler;
    }
}
