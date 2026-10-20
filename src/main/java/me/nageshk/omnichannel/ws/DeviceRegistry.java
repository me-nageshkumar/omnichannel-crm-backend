package me.nageshk.omnichannel.ws;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** Tracks the single live extension connection per onboarded number. */
@Component
public class DeviceRegistry {

    private final ConcurrentHashMap<String, DeviceSession> byNumber = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, DeviceSession> bySessionId = new ConcurrentHashMap<>();

    /** @return the previous session for this number, if one was replaced. */
    public synchronized Optional<DeviceSession> register(DeviceSession session) {
        DeviceSession previous = byNumber.put(session.phoneNumberId(), session);
        bySessionId.put(session.sessionId(), session);
        if (previous != null) {
            bySessionId.remove(previous.sessionId());
        }
        return Optional.ofNullable(previous);
    }

    /** Removes by socket id. Only clears the number mapping if it still points at this same session. */
    public synchronized Optional<DeviceSession> unregister(String sessionId) {
        DeviceSession removed = bySessionId.remove(sessionId);
        if (removed != null) {
            byNumber.remove(removed.phoneNumberId(), removed);
        }
        return Optional.ofNullable(removed);
    }

    public Optional<DeviceSession> find(String phoneNumberId) {
        return Optional.ofNullable(byNumber.get(phoneNumberId));
    }

    /** Every live connection. */
    public List<DeviceSession> all() {
        return List.copyOf(byNumber.values());
    }

    public Optional<DeviceSession> findBySessionId(String sessionId) {
        return Optional.ofNullable(bySessionId.get(sessionId));
    }
}
