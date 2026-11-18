package me.nageshk.omnichannel.ai.knowledge;

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

/** The knowledge worker's own pool and tick thread, so a slow upload never holds the AI turn pool. */
@Configuration
class KnowledgeSchedule {

    @Bean
    ThreadPoolTaskExecutor aiKnowledgeExecutor(AiProperties props) {
        ThreadPoolTaskExecutor e = new ThreadPoolTaskExecutor();
        e.setCorePoolSize(props.knowledge().poolSize());
        e.setMaxPoolSize(props.knowledge().poolSize());
        e.setQueueCapacity(100);
        e.setThreadNamePrefix("ai-knowledge-");
        e.setDaemon(true);
        e.setWaitForTasksToCompleteOnShutdown(true);
        e.setAwaitTerminationSeconds(30);
        return e;
    }

    @Bean
    ThreadPoolTaskScheduler aiKnowledgeScheduler() {
        ThreadPoolTaskScheduler s = new ThreadPoolTaskScheduler();
        s.setPoolSize(1);
        s.setThreadNamePrefix("ai-knowledge-tick-");
        s.setDaemon(true);
        s.setWaitForTasksToCompleteOnShutdown(true);
        s.setAwaitTerminationSeconds(30);
        return s;
    }

    /** Ticks on a schedule; tests switch it off and tick by hand. */
    @Component
    @ConditionalOnProperty(name = "omnichannel.ai.auto-tick", havingValue = "true", matchIfMissing = true)
    static class Tick {
        private static final Logger log = LoggerFactory.getLogger(Tick.class);
        private final KnowledgeWorker worker;

        Tick(KnowledgeWorker worker) {
            this.worker = worker;
        }

        @Scheduled(fixedDelayString = "${omnichannel.ai.knowledge.tick:5s}", scheduler = "aiKnowledgeScheduler")
        void tick() {
            try {
                worker.tick();
            } catch (RuntimeException e) {
                log.warn("Knowledge tick failed", e);
            }
        }
    }
}
