package me.nageshk.omnichannel.service.contact;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * The thread {@link ContactIdResolver} works on: one, so device calls are paced and resolutions never race each
 * other, and never the ingest thread, which must not wait on a device. Work still queued when the application stops
 * is dropped; the next message (or a new request) asks again.
 */
@Configuration
class ContactResolverConfig {

    @Bean
    ThreadPoolTaskExecutor contactResolverExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setThreadNamePrefix("contact-resolver-");
        executor.setDaemon(true);
        return executor;
    }
}
