package me.nageshk.omnichannel.service.page;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import me.nageshk.omnichannel.service.cloud.CloudMediaService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Daily at 03:45: drops the preview images of expired message pages, and cloud media uploaded over 24 hours ago that
 * no page refers to (an upload whose message was never sent).
 */
@Component
public class MessagePageSweeper {

    private static final Logger log = LoggerFactory.getLogger(MessagePageSweeper.class);

    private final MessagePageService pages;
    private final CloudMediaService media;
    private final Clock clock;

    public MessagePageSweeper(MessagePageService pages, CloudMediaService media, Clock clock) {
        this.pages = pages;
        this.media = media;
        this.clock = clock;
    }

    @Scheduled(cron = "0 45 3 * * *")
    public void sweep() {
        Instant now = Instant.now(clock);
        try {
            int n = pages.sweepExpiredPreviews(now);
            if (n > 0) log.info("Message page sweep dropped {} expired preview image(s)", n);
        } catch (RuntimeException e) {
            log.warn("Message page preview sweep failed", e);
        }
        try {
            int n = media.sweepOrphans(now.minus(Duration.ofHours(24)));
            if (n > 0) log.info("Cloud media sweep deleted {} orphaned file(s)", n);
        } catch (RuntimeException e) {
            log.warn("Cloud media sweep failed", e);
        }
    }
}
