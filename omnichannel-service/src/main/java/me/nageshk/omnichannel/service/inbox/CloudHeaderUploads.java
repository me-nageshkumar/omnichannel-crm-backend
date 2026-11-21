package me.nageshk.omnichannel.service.inbox;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * A campaign's template header file, uploaded to Meta once per number and reused for every recipient (spec
 * 2026-11-21 §5.3). Meta keeps an upload 30 days; an id is reused for 29, so an expired one is never sent. Kept in
 * memory: a restart costs one more upload.
 */
@Component
public class CloudHeaderUploads {

    static final Duration KEEP = Duration.ofDays(29);

    private record Uploaded(String metaMediaId, Instant at) {
    }

    private final Map<String, Uploaded> uploads = new ConcurrentHashMap<>();
    private final Clock clock;

    CloudHeaderUploads(Clock clock) {
        this.clock = clock;
    }

    /** Meta's id of the app file {@code headerMediaId} on the number, uploading it with {@code upload} when needed. */
    public String metaMediaId(String phoneNumberId, String headerMediaId, Supplier<String> upload) {
        String key = phoneNumberId + "/" + headerMediaId;
        Instant now = clock.instant();
        Uploaded known = uploads.get(key);
        if (known != null && known.at().plus(KEEP).isAfter(now)) return known.metaMediaId();
        String id = upload.get();
        uploads.put(key, new Uploaded(id, now));
        return id;
    }
}
