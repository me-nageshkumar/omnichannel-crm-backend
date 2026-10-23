package me.nageshk.omnichannel.service.config;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Kept separate from WebSocketConfig so the handler (which needs the scheduler) has no cycle with it.
 *
 * <p>Also switches on {@code @Scheduled}. Boot's task-scheduling auto-configuration backs off because the
 * {@code wsScheduler} {@code ScheduledExecutorService} bean exists, so a {@code taskScheduler} is defined here; without
 * it {@code @Scheduled} would run on {@code wsScheduler}, the single-thread executor that times out WebSocket
 * authentication. This scheduler is also the only {@code TaskExecutor} bean, so if {@code @EnableAsync} is added
 * later, define an {@code applicationTaskExecutor} too.
 */
@Configuration
@EnableScheduling
public class SchedulerConfig {

    @Bean(destroyMethod = "shutdownNow")
    public ScheduledExecutorService wsScheduler() {
        return Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ws-auth-timer");
            t.setDaemon(true);
            return t;
        });
    }

    @Bean
    public ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("scheduling-");
        scheduler.setDaemon(true);
        return scheduler;
    }
}
