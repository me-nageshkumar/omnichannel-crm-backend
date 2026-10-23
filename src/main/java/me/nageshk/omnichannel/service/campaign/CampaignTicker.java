package me.nageshk.omnichannel.service.campaign;

import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs the campaign tick on {@code campaignScheduler}; tests switch it off and tick by hand. */
@Component
@ConditionalOnProperty(name = "omnichannel.campaign.auto-tick", havingValue = "true", matchIfMissing = true)
public class CampaignTicker {

    private static final Logger log = LoggerFactory.getLogger(CampaignTicker.class);

    private final CampaignRunner runner;
    private final Clock clock;

    CampaignTicker(CampaignRunner runner, Clock clock) {
        this.runner = runner;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${omnichannel.campaign.tick:10s}", scheduler = "campaignScheduler")
    void tick() {
        runner.tick();
    }

    /** Sends that were in flight when the application stopped never finished: fail them before the first tick. */
    @EventListener(ApplicationReadyEvent.class)
    void atStartup() {
        try {
            runner.failStuck(clock.instant());
        } catch (RuntimeException e) {
            log.warn("could not fail the campaign sends interrupted by the last shutdown", e);
        }
    }
}
