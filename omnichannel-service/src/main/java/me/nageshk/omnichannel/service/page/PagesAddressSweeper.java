package me.nageshk.omnichannel.service.page;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

/**
 * Checks every brand's link address once a day (spec 2026-11-18 link addresses §5.4), on a thread of its own: a pass
 * over many dead domains takes minutes and must not hold up the shared scheduler's stream pings and sweepers.
 */
@Component
public class PagesAddressSweeper {

    private static final Logger log = LoggerFactory.getLogger(PagesAddressSweeper.class);

    private final PagesAddressChecker checker;

    public PagesAddressSweeper(PagesAddressChecker checker) {
        this.checker = checker;
    }

    @Scheduled(cron = "0 15 4 * * *", scheduler = "pagesAddressScheduler")
    void daily() {
        try {
            int n = checker.recheckAll();
            if (n > 0) log.info("checked {} link address(es)", n);
        } catch (RuntimeException e) {
            log.warn("the daily link address check failed", e);
        }
    }

    @Configuration
    static class SchedulerConfig {
        @Bean(destroyMethod = "shutdown")
        ThreadPoolTaskScheduler pagesAddressScheduler() {
            ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
            scheduler.setPoolSize(1);
            scheduler.setThreadNamePrefix("pages-address-");
            scheduler.setDaemon(true);
            return scheduler;
        }
    }
}
