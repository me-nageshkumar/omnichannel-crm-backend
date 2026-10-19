package me.nageshk.omnichannel.access;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Every role, held in memory so authorities can be resolved on each request without a query. Loads
 * all roles on first use and again after {@link #evictAll()}, which every code path that creates,
 * edits or deletes a role must call. A load that overlaps an eviction is used once but not cached,
 * so it cannot outlive the edit that triggered the eviction. Single-instance only.
 */
@Component
public class RoleCache {

    private record Snapshot(Map<String, Role> byId, Map<String, Role> byName) {}

    private final RoleRepository roles;
    private final AtomicLong generation = new AtomicLong();
    private volatile Snapshot snapshot;

    public RoleCache(RoleRepository roles) {
        this.roles = roles;
    }

    public Optional<Role> byId(String id) {
        return id == null ? Optional.empty() : Optional.ofNullable(snapshot().byId().get(id));
    }

    public Optional<Role> byName(String name) {
        return name == null ? Optional.empty() : Optional.ofNullable(snapshot().byName().get(name));
    }

    public synchronized void evictAll() {
        generation.incrementAndGet();
        snapshot = null;
    }

    private Snapshot snapshot() {
        Snapshot current = snapshot;
        if (current == null) {
            long loadedAt = generation.get();
            var all = roles.findAll();
            current = new Snapshot(
                    all.stream().collect(Collectors.toUnmodifiableMap(Role::getId, Function.identity())),
                    all.stream().collect(Collectors.toUnmodifiableMap(Role::getName, Function.identity())));
            synchronized (this) {
                if (generation.get() == loadedAt) snapshot = current;
            }
        }
        return current;
    }
}
