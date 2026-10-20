package me.nageshk.omnichannel.access;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Every authority, held in memory so codes can be resolved without a query. Loads all authorities on
 * first use and again after {@link #evictAll()}, which every code path that creates, edits or deletes
 * an authority must call; that also evicts the {@link RoleCache}, whose roles are checked against this
 * catalogue. A load that overlaps an eviction is used once but not cached. Single-instance only.
 */
@Component
public class AuthorityCatalog implements AuthorityLevels {

    private final AuthorityDefinitionRepository authorities;
    private final RoleCache roleCache;
    private final AtomicLong generation = new AtomicLong();
    private volatile Map<String, AuthorityDefinition> snapshot;

    public AuthorityCatalog(AuthorityDefinitionRepository authorities, RoleCache roleCache) {
        this.authorities = authorities;
        this.roleCache = roleCache;
    }

    public Optional<AuthorityDefinition> byCode(String code) {
        return code == null ? Optional.empty() : Optional.ofNullable(snapshot().get(code));
    }

    @Override
    public Optional<RoleLevel> levelOf(String code) {
        return byCode(code).map(AuthorityDefinition::getLevel);
    }

    /** Every authority, in no particular order. */
    public Collection<AuthorityDefinition> all() {
        return snapshot().values();
    }

    public synchronized void evictAll() {
        generation.incrementAndGet();
        snapshot = null;
        roleCache.evictAll();
    }

    private Map<String, AuthorityDefinition> snapshot() {
        Map<String, AuthorityDefinition> current = snapshot;
        if (current == null) {
            long loadedAt = generation.get();
            current = authorities.findAll().stream().collect(
                    Collectors.toUnmodifiableMap(AuthorityDefinition::getCode, Function.identity()));
            synchronized (this) {
                if (generation.get() == loadedAt) snapshot = current;
            }
        }
        return current;
    }
}
