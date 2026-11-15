package me.nageshk.omnichannel.ai.call;

import me.nageshk.omnichannel.ai.config.AiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

/** The AI turns' own pool and tick thread, so a slow provider never holds up chatbots or the other schedulers. */
@Configuration
class AiSchedulerConfig {

    @Bean
    ThreadPoolTaskExecutor aiExecutor(AiProperties props) {
        ThreadPoolTaskExecutor e = new ThreadPoolTaskExecutor();
        e.setCorePoolSize(props.poolSize());
        e.setMaxPoolSize(props.poolSize());
        e.setQueueCapacity(500);
        e.setThreadNamePrefix("ai-");
        e.setDaemon(true);
        e.setWaitForTasksToCompleteOnShutdown(true);
        e.setAwaitTerminationSeconds(30);
        return e;
    }

    @Bean
    ThreadPoolTaskScheduler aiScheduler() {
        ThreadPoolTaskScheduler s = new ThreadPoolTaskScheduler();
        s.setPoolSize(1);
        s.setThreadNamePrefix("ai-tick-");
        s.setDaemon(true);
        s.setWaitForTasksToCompleteOnShutdown(true);
        s.setAwaitTerminationSeconds(30);
        return s;
    }

    /** Ticks on a schedule; tests switch it off and tick by hand. */
    @Component
    @ConditionalOnProperty(name = "omnichannel.ai.auto-tick", havingValue = "true", matchIfMissing = true)
    static class Schedule {
        private static final Logger log = LoggerFactory.getLogger(Schedule.class);
        private final AiTurnWorker worker;

        Schedule(AiTurnWorker worker) {
            this.worker = worker;
        }

        @Scheduled(fixedDelayString = "${omnichannel.ai.tick:2s}", scheduler = "aiScheduler")
        void tick() {
            try {
                worker.tick();
            } catch (RuntimeException e) {
                log.warn("AI tick failed", e);
            }
        }
    }
}
