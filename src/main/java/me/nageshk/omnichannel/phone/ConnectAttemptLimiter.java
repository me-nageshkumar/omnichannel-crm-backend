package me.nageshk.omnichannel.phone;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** Failed connect-code attempts per client IP: {@link #MAX_FAILURES} inside {@link #WINDOW} blocks that IP. */
@Component
public class ConnectAttemptLimiter {

    public static final int MAX_FAILURES = 10;
    public static final Duration WINDOW = Duration.ofMinutes(15);

    private final Clock clock;
    private final ConcurrentHashMap<String, Deque<Instant>> failures = new ConcurrentHashMap<>();

    public ConnectAttemptLimiter(Clock clock) {
        this.clock = clock;
    }

    /** Seconds until the IP may try again, or 0 if it may now. */
    public long blockedForSeconds(String ip) {
        Deque<Instant> q = recent(ip);
        synchronized (q) {
            if (q.size() < MAX_FAILURES) return 0;
            Instant freeAt = q.peekFirst().plus(WINDOW);
            return Math.max(1, Duration.between(Instant.now(clock), freeAt).toSeconds());
        }
    }

    public void recordFailure(String ip) {
        Deque<Instant> q = recent(ip);
        synchronized (q) {
            q.addLast(Instant.now(clock));
        }
    }

    public void clearAll() {
        failures.clear();
    }

    private Deque<Instant> recent(String ip) {
        Deque<Instant> q = failures.computeIfAbsent(ip, k -> new ArrayDeque<>());
        Instant cutoff = Instant.now(clock).minus(WINDOW);
        synchronized (q) {
            while (!q.isEmpty() && !q.peekFirst().isAfter(cutoff)) q.removeFirst();
        }
        return q;
    }
}
