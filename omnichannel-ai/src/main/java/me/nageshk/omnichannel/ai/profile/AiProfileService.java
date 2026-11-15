package me.nageshk.omnichannel.ai.profile;

import java.net.URI;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import me.nageshk.omnichannel.ai.api.AiCapabilities;
import me.nageshk.omnichannel.ai.api.AiProfileUsage;
import me.nageshk.omnichannel.ai.connection.AiConnection;
import me.nageshk.omnichannel.ai.connection.AiConnectionRepository;
import me.nageshk.omnichannel.ai.profile.AiProfileViews.McpSave;
import me.nageshk.omnichannel.ai.profile.AiProfileViews.Save;
import me.nageshk.omnichannel.ai.profile.AiProfileViews.View;
import me.nageshk.omnichannel.ai.provider.AiProvider;
import me.nageshk.omnichannel.ai.provider.AiProviders;
import me.nageshk.omnichannel.common.api.CodedException;
import me.nageshk.omnichannel.common.api.DuplicateException;
import me.nageshk.omnichannel.common.api.NotFoundException;
import me.nageshk.omnichannel.common.api.ValidationException;
import me.nageshk.omnichannel.common.secret.SecretBox;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class AiProfileService {

    /** A profile with the connection and provider it runs on, for a turn. */
    public record ResolvedProfile(AiProfile profile, AiConnection connection, AiProvider provider) {}

    private static final Set<String> EFFORTS = Set.of("minimal", "low", "medium", "high");
    private static final Pattern LABEL = Pattern.compile("[a-z0-9_]{1,20}");
    private static final Pattern HEADER = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]{1,100}");
    private static final int MAX_SERVERS = 20;
    private static final int MAX_STORES = 50;

    private final AiProfileRepository repository;
    private final AiConnectionRepository connections;
    private final AiProviders providers;
    private final SecretBox secrets;
    private final ObjectProvider<AiProfileUsage> usage;
    private final Clock clock;

    public AiProfileService(AiProfileRepository repository, AiConnectionRepository connections, AiProviders providers,
            SecretBox secrets, ObjectProvider<AiProfileUsage> usage, Clock clock) {
        this.repository = repository;
        this.connections = connections;
        this.providers = providers;
        this.secrets = secrets;
        this.usage = usage;
        this.clock = clock;
    }

    public List<View> list(String workspaceId) {
        Map<String, String> providerOf = providerByConnection(workspaceId);
        return repository.findByWorkspaceIdOrderByName(workspaceId).stream()
                .map(p -> View.of(p, providerOf.get(p.getConnectionId()))).toList();
    }

    public View get(String workspaceId, String id) {
        AiProfile p = find(workspaceId, id);
        return View.of(p, providerOf(workspaceId, p));
    }

    /** The profile with its connection and provider, for the turn runner. */
    public ResolvedProfile resolved(String workspaceId, String profileId) {
        AiProfile p = find(workspaceId, profileId);
        AiConnection c = connections.findByIdAndWorkspaceId(p.getConnectionId(), workspaceId)
                .orElseThrow(() -> new NotFoundException("AI connection not found"));
        return new ResolvedProfile(p, c, providers.of(c.getProvider()));
    }

    public View create(String workspaceId, String customerId, String userId, Save in) {
        AiProfile p = new AiProfile();
        p.setWorkspaceId(workspaceId);
        p.setCustomerId(customerId);
        return apply(p, workspaceId, userId, in, false);
    }

    public View replace(String workspaceId, String id, String userId, Save in) {
        AiProfile p = find(workspaceId, id);
        if (in.version() != null && !Objects.equals(in.version(), p.getVersion())) {
            throw new OptimisticLockingFailureException("AI profile changed since it was read");
        }
        return apply(p, workspaceId, userId, in, true);
    }

    public void delete(String workspaceId, String id) {
        AiProfile p = find(workspaceId, id);
        AiProfileUsage u = usage.getIfAvailable();
        List<String> using = u == null ? List.of() : u.chatbotsUsing(workspaceId, id);
        if (!using.isEmpty()) {
            throw new CodedException(HttpStatus.CONFLICT, "ai_profile_in_use", null,
                    "Used by chatbots: " + String.join(", ", using));
        }
        repository.delete(p);
    }

    private View apply(AiProfile p, String workspaceId, String userId, Save in, boolean existing) {
        Map<String, String> errors = new LinkedHashMap<>();
        AiConnection connection = null;
        if (in.connectionId() == null || in.connectionId().isBlank()) {
            errors.put("connectionId", "is required");
        } else {
            connection = connections.findByIdAndWorkspaceId(in.connectionId(), workspaceId).orElse(null);
            if (connection == null) errors.put("connectionId", "is not an AI connection of this brand");
        }
        String name = in.name() == null ? "" : in.name().trim();
        if (name.isEmpty()) errors.put("name", "is required");
        else if (name.length() > 100) errors.put("name", "must be at most 100 characters");
        if (in.model() == null || in.model().isBlank()) errors.put("model", "is required");
        else if (in.model().length() > 200) errors.put("model", "must be at most 200 characters");
        if (in.instructions() != null && in.instructions().length() > 20000) {
            errors.put("instructions", "must be at most 20000 characters");
        }
        if (in.reasoningEffort() != null && !EFFORTS.contains(in.reasoningEffort())) {
            errors.put("reasoningEffort", "must be one of minimal, low, medium, high");
        }
        if (in.maxOutputTokens() != null && (in.maxOutputTokens() < 1 || in.maxOutputTokens() > 128000)) {
            errors.put("maxOutputTokens", "must be between 1 and 128000");
        }
        if (in.temperature() != null && (in.temperature().isNaN() || in.temperature() < 0 || in.temperature() > 2)) {
            errors.put("temperature", "must be between 0 and 2");
        }
        List<String> stores = clean(in.vectorStoreIds());
        if (stores.size() > MAX_STORES) errors.put("vectorStoreIds", "at most " + MAX_STORES + " vector stores");
        else if (stores.stream().anyMatch(s -> s.length() > 200)) errors.put("vectorStoreIds", "an id is too long");

        Map<String, McpServer> stored = new HashMap<>();
        if (existing) p.getMcpServers().forEach(s -> stored.put(s.getId(), s));
        List<McpSave> mcpIn = in.mcpServers() == null ? List.of() : in.mcpServers();
        List<McpServer> servers = new ArrayList<>();
        if (mcpIn.size() > MAX_SERVERS) errors.put("mcpServers", "at most " + MAX_SERVERS + " servers");
        else servers = mcpServers(mcpIn, stored, errors);

        if (connection != null) capabilities(in, stores, mcpIn, providers.of(connection.getProvider()).capabilities(), errors);
        if (!errors.isEmpty()) throw new ValidationException(errors);

        if (!name.equals(p.getName())
                && repository.findByWorkspaceIdAndName(workspaceId, name).filter(o -> !o.getId().equals(p.getId())).isPresent()) {
            throw duplicate(name);
        }
        p.setName(name);
        p.setConnectionId(connection.getId());
        p.setModel(in.model().trim());
        p.setInstructions(in.instructions() == null || in.instructions().isBlank() ? null : in.instructions());
        p.setReasoningEffort(in.reasoningEffort());
        p.setMaxOutputTokens(in.maxOutputTokens());
        p.setTemperature(in.temperature());
        p.setWebSearch(Boolean.TRUE.equals(in.webSearch()));
        p.setVectorStoreIds(stores);
        p.setMcpServers(servers);
        p.setUpdatedAt(clock.instant());
        p.setUpdatedBy(userId);
        try {
            AiProfile saved = repository.save(p);
            return View.of(saved, connection.getProvider());
        } catch (DuplicateKeyException raced) {
            throw duplicate(name);
        }
    }

    private List<McpServer> mcpServers(List<McpSave> in, Map<String, McpServer> stored, Map<String, String> errors) {
        List<McpServer> out = new ArrayList<>();
        Set<String> labels = new HashSet<>();
        for (int i = 0; i < in.size(); i++) {
            McpSave s = in.get(i);
            String at = "mcpServers." + i + ".";
            McpServer old = null;
            if (s.id() != null && !s.id().isBlank()) {
                old = stored.get(s.id());
                if (old == null) errors.put(at + "id", "is not a server of this profile");
            }
            if (s.label() == null || !LABEL.matcher(s.label()).matches()) {
                errors.put(at + "label", "must be 1 to 20 characters: a-z, 0-9 and _");
            } else if (!labels.add(s.label())) {
                errors.put(at + "label", "is used by another server of this profile");
            }
            String url = s.url() == null ? "" : s.url().trim();
            if (!validUrl(url)) errors.put(at + "url", "must be an https URL of at most 2000 characters");
            if (s.connectBy() == null) errors.put(at + "connectBy", "is required");
            McpAuth auth = s.auth() == null ? McpAuth.NONE : s.auth();
            String header = s.headerName() == null ? null : s.headerName().trim();
            if (auth == McpAuth.HEADER && (header == null || !HEADER.matcher(header).matches())) {
                errors.put(at + "headerName", "must be a valid HTTP header name");
            }
            boolean newSecret = s.secret() != null && !s.secret().isBlank();
            if (newSecret && s.secret().length() > 4000) errors.put(at + "secret", "is too long");
            boolean keep = old != null && old.getSecret() != null && old.getAuth() == auth;
            if (auth != McpAuth.NONE && !newSecret && !keep) errors.put(at + "secret", "is required");
            List<String> tools = clean(s.allowedTools());
            if (tools.size() > 50) errors.put(at + "allowedTools", "at most 50 tools");
            else if (tools.stream().anyMatch(t -> t.length() > 200)) errors.put(at + "allowedTools", "a tool name is too long");

            McpServer m = new McpServer();
            m.setId(old != null ? old.getId() : UUID.randomUUID().toString().replace("-", "").substring(0, 12));
            m.setLabel(s.label());
            m.setUrl(url);
            m.setConnectBy(s.connectBy());
            m.setAuth(auth);
            m.setHeaderName(auth == McpAuth.HEADER ? header : null);
            m.setAllowedTools(tools);
            if (auth != McpAuth.NONE && errors.isEmpty()) {
                if (newSecret) {
                    m.setSecret(secrets.seal(s.secret()));
                    m.setSecretHint(SecretBox.hint(s.secret()));
                    m.setSecretSetAt(clock.instant());
                } else {
                    m.setSecret(old.getSecret());
                    m.setSecretHint(old.getSecretHint());
                    m.setSecretSetAt(old.getSecretSetAt());
                }
            }
            out.add(m);
        }
        return out;
    }

    private static void capabilities(Save in, List<String> stores, List<McpSave> mcp, AiCapabilities caps,
            Map<String, String> errors) {
        if (!stores.isEmpty() && !caps.vectorStores()) errors.putIfAbsent("vectorStoreIds", "This provider has no vector stores");
        if (in.temperature() != null && !caps.temperature()) {
            errors.putIfAbsent("temperature", "This provider does not take a temperature");
        }
        if (Boolean.TRUE.equals(in.webSearch()) && !caps.webSearch()) {
            errors.putIfAbsent("webSearch", "This provider has no web search");
        }
        if (in.reasoningEffort() != null && !caps.reasoningEffort()) {
            errors.putIfAbsent("reasoningEffort", "This provider does not take a reasoning effort");
        }
        if (!caps.hostedMcp()) {
            for (int i = 0; i < mcp.size(); i++) {
                if (mcp.get(i).connectBy() == ConnectBy.PROVIDER) {
                    errors.putIfAbsent("mcpServers." + i + ".connectBy",
                            "This provider cannot connect to MCP servers itself; choose Our app");
                }
            }
        }
    }

    private static boolean validUrl(String url) {
        if (url.isEmpty() || url.length() > 2000) return false;
        try {
            URI u = URI.create(url);
            return "https".equalsIgnoreCase(u.getScheme()) && u.getHost() != null && !u.getHost().isBlank();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static List<String> clean(List<String> in) {
        if (in == null) return new ArrayList<>();
        return new ArrayList<>(in.stream().filter(Objects::nonNull).map(String::trim).filter(s -> !s.isEmpty())
                .distinct().toList());
    }

    private AiProfile find(String workspaceId, String id) {
        return repository.findByIdAndWorkspaceId(id, workspaceId)
                .orElseThrow(() -> new NotFoundException("AI profile not found"));
    }

    private String providerOf(String workspaceId, AiProfile p) {
        return connections.findByIdAndWorkspaceId(p.getConnectionId(), workspaceId).map(AiConnection::getProvider)
                .orElse(null);
    }

    private Map<String, String> providerByConnection(String workspaceId) {
        Map<String, String> m = new HashMap<>();
        connections.findByWorkspaceIdOrderByName(workspaceId).forEach(c -> m.put(c.getId(), c.getProvider()));
        return m;
    }

    private static DuplicateException duplicate(String name) {
        return new DuplicateException("name", "An AI profile named '" + name + "' already exists");
    }
}
