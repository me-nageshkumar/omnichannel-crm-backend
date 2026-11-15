package me.nageshk.omnichannel.ai.connection;

import java.time.Instant;
import me.nageshk.omnichannel.ai.api.AiCapabilities;
import me.nageshk.omnichannel.ai.provider.AiProvider;

/** The wire shapes of connections: a key travels in a request only, never in a view. */
public final class AiConnectionViews {

    private AiConnectionViews() {}

    /** {@code apiKey} is required on create and, when present on replace, sets a new key; {@code version} guards a replace. */
    public record Save(String name, String provider, String apiKey, Long version) {}

    public record View(String id, String name, String provider, String keyHint, Instant keySetAt,
            AiConnectionStatus status, Instant checkedAt, String lastError, Long version) {

        static View of(AiConnection c) {
            return new View(c.getId(), c.getName(), c.getProvider(), c.getKeyHint(), c.getKeySetAt(), c.getStatus(),
                    c.getCheckedAt(), c.getLastError(), c.getVersion());
        }
    }

    public record ProviderView(String id, String label, AiCapabilities capabilities) {

        static ProviderView of(AiProvider p) {
            return new ProviderView(p.id(), p.label(), p.capabilities());
        }
    }
}
