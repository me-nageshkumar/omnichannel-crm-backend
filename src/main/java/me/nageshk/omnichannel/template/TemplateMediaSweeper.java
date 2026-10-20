package me.nageshk.omnichannel.template;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Daily at 03:30: deletes template uploads older than 24 hours that no template refers to. */
@Component
public class TemplateMediaSweeper {

    private static final Logger log = LoggerFactory.getLogger(TemplateMediaSweeper.class);

    private final TemplateMediaService media;
    private final Clock clock;

    public TemplateMediaSweeper(TemplateMediaService media, Clock clock) {
        this.media = media;
        this.clock = clock;
    }

    @Scheduled(cron = "0 30 3 * * *")
    public void sweep() {
        try {
            int n = media.sweepOrphans(Instant.now(clock).minus(Duration.ofHours(24)));
            if (n > 0) log.info("Template media sweep deleted {} orphaned file(s)", n);
        } catch (RuntimeException e) {
            log.warn("Template media sweep failed", e);
        }
    }
}
