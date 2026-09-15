package in.redlava.wabridge.events;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Keeps the last N WhatsApp events per user in memory so REST clients (and humans) can poll them. */
@Component
public class RecentEventsStore {

    public record StoredEvent(long seq, Instant at, String name, JsonNode payload) {}

    public static final int CAPACITY = 200;

    private final Map<String, Deque<StoredEvent>> byUser = new ConcurrentHashMap<>();
    private long seq = 0;

    @EventListener
    public synchronized void on(WhatsAppEvent event) {
        Deque<StoredEvent> q = byUser.computeIfAbsent(event.username(), u -> new ArrayDeque<>());
        q.addLast(new StoredEvent(++seq, Instant.now(), event.name(), event.payload()));
        while (q.size() > CAPACITY) q.pollFirst();
    }

    /** Newest last. Events with seq greater than {@code afterSeq} only; at most {@code limit}. */
    public synchronized List<StoredEvent> recent(String username, long afterSeq, int limit) {
        Deque<StoredEvent> q = byUser.getOrDefault(username, new ArrayDeque<>());
        List<StoredEvent> out = new ArrayList<>();
        for (StoredEvent e : q) {
            if (e.seq() > afterSeq) out.add(e);
        }
        if (out.size() > limit) {
            out = new ArrayList<>(out.subList(out.size() - limit, out.size()));
        }
        return out;
    }
}
