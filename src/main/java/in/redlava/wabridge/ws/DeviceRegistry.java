package in.redlava.wabridge.ws;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** Tracks the single live extension connection per user. */
@Component
public class DeviceRegistry {

    private final ConcurrentHashMap<String, DeviceSession> byUser = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, DeviceSession> bySessionId = new ConcurrentHashMap<>();

    /** @return the previous session for this user, if one was replaced. */
    public synchronized Optional<DeviceSession> register(DeviceSession session) {
        DeviceSession previous = byUser.put(session.username(), session);
        bySessionId.put(session.sessionId(), session);
        if (previous != null) {
            bySessionId.remove(previous.sessionId());
        }
        return Optional.ofNullable(previous);
    }

    /** Removes by socket id. Only clears the user mapping if it still points at this same session. */
    public synchronized Optional<DeviceSession> unregister(String sessionId) {
        DeviceSession removed = bySessionId.remove(sessionId);
        if (removed != null) {
            byUser.remove(removed.username(), removed);
        }
        return Optional.ofNullable(removed);
    }

    public Optional<DeviceSession> find(String username) {
        return Optional.ofNullable(byUser.get(username));
    }

    public Optional<DeviceSession> findBySessionId(String sessionId) {
        return Optional.ofNullable(bySessionId.get(sessionId));
    }
}
