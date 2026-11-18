package me.nageshk.omnichannel.ai.connection;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import me.nageshk.omnichannel.ai.api.AiCapabilities;
import me.nageshk.omnichannel.ai.api.AiConnectionField;
import me.nageshk.omnichannel.ai.provider.AiProvider;

/** The wire shapes of connections: a key travels in a request only, never in a view. */
public final class AiConnectionViews {

    private AiConnectionViews() {}

    /**
     * {@code apiKey} is required on create and, when present on replace, sets a new key; {@code settings} are the
     * provider's plain fields: absent on replace keeps the stored ones, an empty map clears them; {@code version} guards a replace.
     */
    public record Save(String name, String provider, String apiKey, Map<String, String> settings, Long version) {}

    public record View(String id, String name, String provider, String keyHint, Instant keySetAt,
            AiConnectionStatus status, Instant checkedAt, String lastError, Map<String, String> settings,
            Long version) {

        static View of(AiConnection c) {
            return new View(c.getId(), c.getName(), c.getProvider(), c.getKeyHint(), c.getKeySetAt(), c.getStatus(),
                    c.getCheckedAt(), c.getLastError(), c.getSettings(), c.getVersion());
        }
    }

    public record ProviderView(String id, String label, AiCapabilities capabilities,
            List<AiConnectionField> connectionFields, boolean knowledgeBases) {

        static ProviderView of(AiProvider p) {
            return new ProviderView(p.id(), p.label(), p.capabilities(), p.connectionFields(),
                    p instanceof me.nageshk.omnichannel.ai.provider.KnowledgeStoreProvider);
        }
    }
}
