package me.nageshk.omnichannel.service.channel.cloud;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Every half minute, processes Cloud events stored a minute ago or more and still not processed. */
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "omnichannel.meta.auto-replay",
        havingValue = "true", matchIfMissing = true)
class CloudWebhookReplay {

    private static final Logger log = LoggerFactory.getLogger(CloudWebhookReplay.class);

    private final CloudWebhookIngest ingest;

    CloudWebhookReplay(CloudWebhookIngest ingest) {
        this.ingest = ingest;
    }

    @Scheduled(fixedDelayString = "${omnichannel.meta.replay-every:PT30S}", initialDelayString = "PT30S")
    void sweep() {
        try {
            int n = ingest.replay(Duration.ofMinutes(1));
            if (n > 0) log.info("replayed {} Meta webhook event(s) that had not been processed", n);
        } catch (RuntimeException e) {
            log.warn("Meta webhook replay failed: {}", e.toString());
        }
    }
}
