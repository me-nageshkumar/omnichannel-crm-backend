package me.nageshk.omnichannel.ai.connection;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import me.nageshk.omnichannel.ai.connection.AiConnectionViews.ProviderView;
import me.nageshk.omnichannel.ai.connection.AiConnectionViews.Save;
import me.nageshk.omnichannel.ai.connection.AiConnectionViews.View;
import me.nageshk.omnichannel.ai.profile.AiProfile;
import me.nageshk.omnichannel.ai.profile.AiProfileRepository;
import me.nageshk.omnichannel.ai.provider.AiCredential;
import me.nageshk.omnichannel.ai.provider.AiModelInfo;
import me.nageshk.omnichannel.ai.provider.AiProvider;
import me.nageshk.omnichannel.ai.provider.AiProviderException;
import me.nageshk.omnichannel.ai.provider.AiProviders;
import me.nageshk.omnichannel.ai.provider.VectorStoreInfo;
import me.nageshk.omnichannel.common.api.CodedException;
import me.nageshk.omnichannel.common.api.DuplicateException;
import me.nageshk.omnichannel.common.api.NotFoundException;
import me.nageshk.omnichannel.common.api.ValidationException;
import me.nageshk.omnichannel.common.secret.SecretBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class AiConnectionService {

    private static final Logger log = LoggerFactory.getLogger(AiConnectionService.class);
    static final String REFUSED = "The provider refused the key";

    private final AiConnectionRepository repository;
    private final AiProviders providers;
    private final SecretBox secrets;
    private final AiProfileRepository profiles;
    private final Clock clock;

    public AiConnectionService(AiConnectionRepository repository, AiProviders providers, SecretBox secrets,
            AiProfileRepository profiles, Clock clock) {
        this.repository = repository;
        this.providers = providers;
        this.secrets = secrets;
        this.profiles = profiles;
        this.clock = clock;
    }

    public List<ProviderView> providers() {
        return providers.all().stream().map(ProviderView::of).toList();
    }

    public List<View> list(String workspaceId) {
        return repository.findByWorkspaceIdOrderByName(workspaceId).stream().map(View::of).toList();
    }

    public View get(String workspaceId, String id) {
        return View.of(find(workspaceId, id));
    }

    public View create(String workspaceId, String customerId, String userId, Save in) {
        Map<String, String> errors = validate(in);
        if (in.apiKey() == null || in.apiKey().isBlank()) errors.put("apiKey", "is required");
        if (!errors.isEmpty()) throw new ValidationException(errors);
        String name = in.name().trim();
        if (repository.findByWorkspaceIdAndName(workspaceId, name).isPresent()) throw duplicate(name);

        AiConnection c = new AiConnection();
        c.setWorkspaceId(workspaceId);
        c.setCustomerId(customerId);
        c.setName(name);
        c.setProvider(in.provider());
        c.setCreatedAt(clock.instant());
        c.setUpdatedBy(userId);
        setKey(c, in.apiKey());
        runVerify(c, in.apiKey());
        return View.of(persist(c));
    }

    public View replace(String workspaceId, String id, String userId, Save in) {
        AiConnection c = find(workspaceId, id);
        Map<String, String> errors = validate(in);
        if (!errors.isEmpty()) throw new ValidationException(errors);
        if (in.version() != null && !Objects.equals(in.version(), c.getVersion())) {
            throw new OptimisticLockingFailureException("AI connection changed since it was read");
        }
        String name = in.name().trim();
        if (!name.equals(c.getName())
                && repository.findByWorkspaceIdAndName(workspaceId, name).filter(o -> !o.getId().equals(id)).isPresent()) {
            throw duplicate(name);
        }
        boolean newKey = in.apiKey() != null && !in.apiKey().isBlank();
        boolean newProvider = !in.provider().equals(c.getProvider());
        if (newProvider && !newKey) {
            throw new ValidationException("apiKey", "is required when the provider changes");
        }
        c.setName(name);
        c.setProvider(in.provider());
        c.setUpdatedBy(userId);
        if (newKey) {
            setKey(c, in.apiKey());
            runVerify(c, in.apiKey());
        }
        return View.of(persist(c));
    }

    public View verify(String workspaceId, String id, String userId) {
        AiConnection c = find(workspaceId, id);
        c.setUpdatedBy(userId);
        runVerify(c, secrets.open(c.getApiKey()));
        return View.of(persist(c));
    }

    public void delete(String workspaceId, String id) {
        AiConnection c = find(workspaceId, id);
        List<String> using = profiles.findByWorkspaceIdAndConnectionId(workspaceId, id).stream()
                .map(AiProfile::getName).sorted().toList();
        if (!using.isEmpty()) {
            throw new CodedException(HttpStatus.CONFLICT, "ai_connection_in_use", null,
                    "Used by profiles: " + String.join(", ", using));
        }
        repository.delete(c);
    }

    public List<AiModelInfo> models(String workspaceId, String id) {
        AiConnection c = find(workspaceId, id);
        return call(c, p -> p.models(credential(c)));
    }

    public List<VectorStoreInfo> vectorStores(String workspaceId, String id) {
        AiConnection c = find(workspaceId, id);
        return call(c, p -> p.vectorStores(credential(c)));
    }

    /** The opened key of a connection of the brand, for a provider call; never exposed over HTTP. */
    public AiCredential credential(String workspaceId, String id) {
        return credential(find(workspaceId, id));
    }

    private AiCredential credential(AiConnection c) {
        return new AiCredential(secrets.open(c.getApiKey()));
    }

    private <T> T call(AiConnection c, java.util.function.Function<AiProvider, T> f) {
        try {
            return f.apply(providers.of(c.getProvider()));
        } catch (AiProviderException e) {
            if (e.code() == AiProviderException.Code.AUTH) {
                throw new CodedException(HttpStatus.BAD_GATEWAY, "ai_provider_refused_key", null, REFUSED);
            }
            throw new CodedException(HttpStatus.BAD_GATEWAY, "ai_provider_error", null, e.getMessage());
        }
    }

    private AiConnection find(String workspaceId, String id) {
        return repository.findByIdAndWorkspaceId(id, workspaceId)
                .orElseThrow(() -> new NotFoundException("AI connection not found"));
    }

    private Map<String, String> validate(Save in) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (in.name() == null || in.name().isBlank()) errors.put("name", "is required");
        else if (in.name().trim().length() > 100) errors.put("name", "must be at most 100 characters");
        if (in.provider() == null || providers.all().stream().noneMatch(p -> p.id().equals(in.provider()))) {
            errors.put("provider", "is not a known AI provider");
        }
        return errors;
    }

    private void setKey(AiConnection c, String plain) {
        c.setApiKey(secrets.seal(plain));
        c.setKeyHint(SecretBox.hint(plain));
        c.setKeySetAt(clock.instant());
    }

    /** Checks the key with the provider; a failed check is recorded on the connection and never fails the save. */
    private void runVerify(AiConnection c, String plainKey) {
        c.setCheckedAt(clock.instant());
        try {
            providers.of(c.getProvider()).verify(new AiCredential(plainKey));
            c.setStatus(AiConnectionStatus.OK);
            c.setLastError(null);
        } catch (AiProviderException e) {
            if (e.code() == AiProviderException.Code.AUTH) {
                c.setStatus(AiConnectionStatus.INVALID);
                c.setLastError(REFUSED);
            } else {
                c.setStatus(AiConnectionStatus.UNVERIFIED);
                c.setLastError(e.getMessage());
            }
            log.info("AI connection '{}' could not be verified: {}", c.getName(), c.getLastError());
        }
    }

    private AiConnection persist(AiConnection c) {
        try {
            return repository.save(c);
        } catch (DuplicateKeyException raced) {
            throw duplicate(c.getName());
        }
    }

    private static DuplicateException duplicate(String name) {
        return new DuplicateException("name", "An AI connection named '" + name + "' already exists");
    }
}
