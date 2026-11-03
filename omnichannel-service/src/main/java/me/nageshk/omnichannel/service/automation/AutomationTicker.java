package me.nageshk.omnichannel.service.automation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs the automation worker's tick on {@code automationScheduler}; tests switch it off and tick by hand. */
@Component
@ConditionalOnProperty(name = "omnichannel.automation.auto-tick", havingValue = "true", matchIfMissing = true)
public class AutomationTicker {

    private static final Logger log = LoggerFactory.getLogger(AutomationTicker.class);

    private final AutomationWorker worker;

    AutomationTicker(AutomationWorker worker) {
        this.worker = worker;
    }

    @Scheduled(fixedDelayString = "${omnichannel.automation.tick:2s}", scheduler = "automationScheduler")
    void tick() {
        try {
            worker.tick();
        } catch (RuntimeException e) {
            log.warn("automation tick failed", e);
        }
    }
}
