package me.nageshk.omnichannel.ai.call;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import me.nageshk.omnichannel.ai.api.AiGateway;
import me.nageshk.omnichannel.ai.api.AiProfileInfo;
import me.nageshk.omnichannel.ai.api.AiProfileRef;
import me.nageshk.omnichannel.ai.api.TurnRequest;
import me.nageshk.omnichannel.ai.api.TurnResult;
import me.nageshk.omnichannel.ai.connection.AiConnection;
import me.nageshk.omnichannel.ai.connection.AiConnectionRepository;
import me.nageshk.omnichannel.ai.profile.AiProfile;
import me.nageshk.omnichannel.ai.profile.AiProfileRepository;
import me.nageshk.omnichannel.ai.provider.AiProviders;
import org.springframework.stereotype.Service;

/** The AI module's door for other modules. Turn submission arrives with the call queue. */
@Service
public class AiGatewayService implements AiGateway {

    private final AiProfileRepository profiles;
    private final AiConnectionRepository connections;
    private final AiProviders providers;

    public AiGatewayService(AiProfileRepository profiles, AiConnectionRepository connections, AiProviders providers) {
        this.profiles = profiles;
        this.connections = connections;
        this.providers = providers;
    }

    @Override
    public String submit(TurnRequest r) {
        throw new UnsupportedOperationException("Task 7");
    }

    @Override
    public Optional<TurnResult> result(String workspaceId, String callId) {
        throw new UnsupportedOperationException("Task 7");
    }

    @Override
    public List<AiProfileRef> profiles(String workspaceId) {
        Map<String, String> providerOf = new HashMap<>();
        connections.findByWorkspaceIdOrderByName(workspaceId).forEach(c -> providerOf.put(c.getId(), c.getProvider()));
        return profiles.findByWorkspaceIdOrderByName(workspaceId).stream()
                .map(p -> new AiProfileRef(p.getId(), p.getName(), providerOf.get(p.getConnectionId()))).toList();
    }

    @Override
    public Optional<AiProfileInfo> profile(String workspaceId, String profileId) {
        Optional<AiProfile> found = profiles.findByIdAndWorkspaceId(profileId, workspaceId);
        if (found.isEmpty()) return Optional.empty();
        AiProfile p = found.get();
        Optional<AiConnection> c = connections.findByIdAndWorkspaceId(p.getConnectionId(), workspaceId);
        if (c.isEmpty()) return Optional.empty();
        return Optional.of(new AiProfileInfo(p.getId(), p.getName(), c.get().getProvider(),
                providers.of(c.get().getProvider()).capabilities(), p.getModel(), p.getInstructions(),
                p.getReasoningEffort(), p.getMaxOutputTokens(), p.getTemperature()));
    }
}
