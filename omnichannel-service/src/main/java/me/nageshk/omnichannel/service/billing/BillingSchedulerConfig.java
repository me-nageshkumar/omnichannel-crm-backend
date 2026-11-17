package me.nageshk.omnichannel.service.billing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

/** The billing ticker's own thread, so a slow tick never holds up the other schedulers. */
@Configuration
class BillingSchedulerConfig {

    @Bean
    ThreadPoolTaskScheduler billingScheduler() {
        ThreadPoolTaskScheduler s = new ThreadPoolTaskScheduler();
        s.setPoolSize(1);
        s.setThreadNamePrefix("billing-tick-");
        s.setDaemon(true);
        s.setWaitForTasksToCompleteOnShutdown(true);
        s.setAwaitTerminationSeconds(30);
        return s;
    }

    /** Ticks on a schedule; tests switch it off and tick by hand. */
    @Component
    @ConditionalOnProperty(name = "omnichannel.billing.auto-tick", havingValue = "true", matchIfMissing = true)
    static class Schedule {
        private static final Logger log = LoggerFactory.getLogger(Schedule.class);
        private final BillingTicker ticker;

        Schedule(BillingTicker ticker) {
            this.ticker = ticker;
        }

        @Scheduled(fixedDelayString = "${omnichannel.billing.tick:30s}", scheduler = "billingScheduler")
        void tick() {
            try {
                ticker.tick();
            } catch (RuntimeException e) {
                log.warn("billing tick failed", e);
            }
        }
    }
}
