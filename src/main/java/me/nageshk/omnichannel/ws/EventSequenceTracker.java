package me.nageshk.omnichannel.ws;

import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Highest durable-event seq seen per user, for de-duplicating replays after a reconnect.
 * In-memory only: after a server restart a replayed burst may be published once more.
 */
@Component
public class EventSequenceTracker {

    private final ConcurrentHashMap<String, Long> last = new ConcurrentHashMap<>();

    public boolean isDuplicate(String phoneNumberId, long seq) {
        Long l = last.get(phoneNumberId);
        return l != null && seq <= l;
    }

    public void record(String phoneNumberId, long seq) {
        last.merge(phoneNumberId, seq, Math::max);
    }

    public OptionalLong last(String phoneNumberId) {
        Long l = last.get(phoneNumberId);
        return l == null ? OptionalLong.empty() : OptionalLong.of(l);
    }
}
