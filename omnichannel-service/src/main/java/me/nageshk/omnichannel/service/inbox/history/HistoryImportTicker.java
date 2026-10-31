package me.nageshk.omnichannel.service.inbox.history;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs the history import tick on {@code historyImportScheduler}; tests switch it off and tick by hand. */
@Component
@ConditionalOnProperty(name = "omnichannel.history.auto-tick", havingValue = "true", matchIfMissing = true)
public class HistoryImportTicker {

    private static final Logger log = LoggerFactory.getLogger(HistoryImportTicker.class);

    private final HistoryImportRunner runner;

    HistoryImportTicker(HistoryImportRunner runner) {
        this.runner = runner;
    }

    @Scheduled(fixedDelayString = "${omnichannel.history.tick:5s}", scheduler = "historyImportScheduler")
    void tick() {
        runner.tick();
    }

    /** Chats being read when the application stopped go back to PENDING before the first tick. */
    @EventListener(ApplicationReadyEvent.class)
    void atStartup() {
        try {
            runner.releaseStuck();
        } catch (RuntimeException e) {
            log.warn("could not release the history import chats interrupted by the last shutdown", e);
        }
    }
}
