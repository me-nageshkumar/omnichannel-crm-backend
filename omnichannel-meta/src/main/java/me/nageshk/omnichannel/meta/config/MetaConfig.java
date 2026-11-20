package me.nageshk.omnichannel.meta.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class MetaConfig {

    /** Processes Meta's changes after the webhook has answered, so Meta gets its 200 fast. */
    @Bean(name = "metaWebhookExecutor", destroyMethod = "shutdown")
    public ThreadPoolTaskExecutor metaWebhookExecutor() {
        ThreadPoolTaskExecutor e = new ThreadPoolTaskExecutor();
        e.setCorePoolSize(4);
        e.setMaxPoolSize(4);
        e.setQueueCapacity(10_000);
        e.setThreadNamePrefix("meta-webhook-");
        // a restart lets queued changes finish; anything cut short is replayed from the event store
        e.setWaitForTasksToCompleteOnShutdown(true);
        e.setAwaitTerminationSeconds(20);
        e.initialize();
        return e;
    }
}
