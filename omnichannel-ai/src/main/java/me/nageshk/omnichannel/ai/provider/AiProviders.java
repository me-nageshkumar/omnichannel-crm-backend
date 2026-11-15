package me.nageshk.omnichannel.ai.provider;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import me.nageshk.omnichannel.common.api.NotFoundException;
import org.springframework.stereotype.Component;

@Component
public class AiProviders {

    private final Map<String, AiProvider> byId = new LinkedHashMap<>();

    public AiProviders(List<AiProvider> providers) {
        for (AiProvider p : providers) {
            if (byId.putIfAbsent(p.id(), p) != null) {
                throw new IllegalStateException("Two AI providers share the id " + p.id());
            }
        }
    }

    public AiProvider of(String id) {
        AiProvider p = id == null ? null : byId.get(id);
        if (p == null) throw new NotFoundException("Unknown AI provider " + id);
        return p;
    }

    public List<AiProvider> all() {
        return List.copyOf(byId.values());
    }
}
