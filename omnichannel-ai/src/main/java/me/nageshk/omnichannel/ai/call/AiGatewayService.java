package me.nageshk.omnichannel.ai.call;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import me.nageshk.omnichannel.ai.api.AiGateway;
import me.nageshk.omnichannel.ai.api.AiProfileInfo;
import me.nageshk.omnichannel.ai.api.AiProfileRef;
import me.nageshk.omnichannel.ai.api.TurnRequest;
import me.nageshk.omnichannel.ai.api.TurnResult;
import me.nageshk.omnichannel.ai.config.AiProperties;
import me.nageshk.omnichannel.ai.connection.AiConnection;
import me.nageshk.omnichannel.ai.connection.AiConnectionRepository;
import me.nageshk.omnichannel.ai.conversation.AiConversation;
import me.nageshk.omnichannel.ai.conversation.AiConversationRepository;
import me.nageshk.omnichannel.ai.profile.AiProfile;
import me.nageshk.omnichannel.ai.profile.AiProfileRepository;
import me.nageshk.omnichannel.ai.profile.AiProfileService;
import me.nageshk.omnichannel.ai.profile.AiProfileService.ResolvedProfile;
import me.nageshk.omnichannel.ai.provider.AiProviders;
import me.nageshk.omnichannel.ai.turn.EffectiveConfig;
import me.nageshk.omnichannel.common.api.NotFoundException;
import me.nageshk.omnichannel.common.api.ValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

/** The AI module's door for other modules: turns go onto the queue here and come back through the listeners. */
@Service
public class AiGatewayService implements AiGateway {

    private static final Logger log = LoggerFactory.getLogger(AiGatewayService.class);

    private final AiProfileRepository profiles;
    private final AiConnectionRepository connections;
    private final AiProviders providers;
    private final AiProfileService profileService;
    private final AiConversationRepository conversations;
    private final AiCallRepository calls;
    private final AiTurnQueue queue;
    private final AiTurnWorker worker;
    private final ThreadPoolTaskExecutor pool;
    private final AiProperties props;
    private final Clock clock;

    public AiGatewayService(AiProfileRepository profiles, AiConnectionRepository connections, AiProviders providers,
            AiProfileService profileService, AiConversationRepository conversations, AiCallRepository calls,
            AiTurnQueue queue, AiTurnWorker worker, @Qualifier("aiExecutor") ThreadPoolTaskExecutor pool,
            AiProperties props, Clock clock) {
        this.profiles = profiles;
        this.connections = connections;
        this.providers = providers;
        this.profileService = profileService;
        this.conversations = conversations;
        this.calls = calls;
        this.queue = queue;
        this.worker = worker;
        this.pool = pool;
        this.props = props;
        this.clock = clock;
    }

    /**
     * Inserts a PENDING call and wakes the worker. A new conversation is created here, so its id is known (and on the
     * result) even when the turn fails. No credential is read or stored: the key is opened when the turn is claimed.
     */
    @Override
    public String submit(TurnRequest r) {
        if (r == null || r.workspaceId() == null || r.profileId() == null || r.profileId().isBlank()) {
            throw new ValidationException("profileId", "is required");
        }
        String ws = r.workspaceId();
        ResolvedProfile rp = profileService.resolved(ws, r.profileId());
        Instant now = clock.instant();
        String conversationId = r.conversationId();
        if (conversationId == null) {
            conversationId = conversations.save(AiConversation.start(r, rp.provider().id(), now)).getId();
        } else if (conversations.findByIdAndWorkspaceId(conversationId, ws).isEmpty()) {
            throw new NotFoundException("AI conversation not found");
        }
        String model = EffectiveConfig.of(rp.profile(), r.overrides(), r.extraInstructions()).model();
        String id = queue.enqueue(AiCall.pending(r, conversationId, rp.provider().id(), model, now));
        if (props.autoTick()) wake();
        return id;
    }

    private void wake() {
        try {
            pool.execute(worker::tick);
        } catch (RuntimeException e) {
            log.info("the AI pool is busy; the next scheduled tick picks the turn up ({})", e.toString());
        }
    }

    /** The result of a finished (DONE or FAILED) call of the brand; empty while it is queued or running. */
    @Override
    public Optional<TurnResult> result(String workspaceId, String callId) {
        return calls.findByIdAndWorkspaceId(callId, workspaceId)
                .filter(c -> c.getStatus() == AiCallStatus.DONE || c.getStatus() == AiCallStatus.FAILED)
                .map(AiCall::toResult);
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
