package me.nageshk.omnichannel.service.campaign;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * The campaign runner's own threads: its tick and each sender's sends wait on devices, so they must never hold up
 * {@code taskScheduler} (inbox pings, the media sweeper), which keeps its name and so stays the default for
 * {@code @Scheduled}.
 */
@Configuration
class CampaignSchedulerConfig {

    @Bean
    ThreadPoolTaskScheduler campaignScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("campaign-");
        scheduler.setDaemon(true);
        // a send in flight finishes (or times out) and is recorded before the application stops: up to a lookup and
        // a send of one rpc timeout each
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(75);
        return scheduler;
    }
}
