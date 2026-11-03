package me.nageshk.omnichannel.service.automation;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * The automation worker's own thread: a tick waits on devices while it sends, so it must never hold up
 * {@code taskScheduler}, the campaign runner or the history import.
 */
@Configuration
class AutomationSchedulerConfig {

    @Bean
    ThreadPoolTaskScheduler automationScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("automation-");
        scheduler.setDaemon(true);
        // a send in flight finishes (or times out) and its step is recorded before the application stops
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(60);
        return scheduler;
    }
}
