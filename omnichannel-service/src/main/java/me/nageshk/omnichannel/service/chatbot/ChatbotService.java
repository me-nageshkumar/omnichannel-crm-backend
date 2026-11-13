package me.nageshk.omnichannel.service.chatbot;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.nageshk.omnichannel.common.api.CodedException;
import me.nageshk.omnichannel.common.api.DuplicateException;
import me.nageshk.omnichannel.common.api.NotFoundException;
import me.nageshk.omnichannel.common.api.ValidationException;
import me.nageshk.omnichannel.common.config.OmnichannelProperties;
import me.nageshk.omnichannel.db.chatbot.Chatbot;
import me.nageshk.omnichannel.db.chatbot.ChatbotRepository;
import me.nageshk.omnichannel.db.chatbot.ChatbotRun;
import me.nageshk.omnichannel.db.chatbot.ChatbotRunStatus;
import me.nageshk.omnichannel.db.chatbot.ChatbotSettings;
import me.nageshk.omnichannel.db.chatbot.ChatbotStatus;
import me.nageshk.omnichannel.db.chatbot.ChatbotVersion;
import me.nageshk.omnichannel.db.chatbot.ChatbotVersionRepository;
import me.nageshk.omnichannel.db.tenant.WorkspaceRepository;
import me.nageshk.omnichannel.db.user.User;
import me.nageshk.omnichannel.db.user.UserRepository;
import me.nageshk.omnichannel.security.auth.AuthenticatedUser;
import me.nageshk.omnichannel.service.automation.AutomationRef;
import me.nageshk.omnichannel.service.chatbot.ChatbotViews.ChatbotSummary;
import me.nageshk.omnichannel.service.chatbot.ChatbotViews.ChatbotView;
import me.nageshk.omnichannel.service.chatbot.ChatbotViews.SaveRequest;
import me.nageshk.omnichannel.service.chatbot.ChatbotViews.SaveResult;
import me.nageshk.omnichannel.service.chatbot.ChatbotViews.VersionView;
import me.nageshk.omnichannel.service.chatbot.flow.Flow;
import me.nageshk.omnichannel.service.chatbot.flow.FlowParser;
import me.nageshk.omnichannel.service.chatbot.node.Problem;
import me.nageshk.omnichannel.service.chatbot.node.Severity;
import me.nageshk.omnichannel.service.chatbot.node.ValidationContext;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * Chatbots (spec §4.1, §6, §8.1): drafts are saved with their problems, published ones are read-only, every save
 * that changes the graph appends a version, publishing needs no ERROR problem.
 */
@Service
public class ChatbotService {

    public static final int MAX_NAME = 60;
    public static final String PUBLISHED = "CHATBOT_PUBLISHED";
    private static final Set<String> CANVAS_ONLY = Set.of("position", "measured", "selected", "dragging", "width",
            "height", "positionAbsolute", "resizing");

    private final ChatbotRepository chatbots;
    private final ChatbotVersionRepository versions;
    private final WorkspaceRepository workspaces;
    private final UserRepository users;
    private final FlowParser parser;
    private final FlowValidator validator;
    private final MongoTemplate mongo;
    private final ObjectMapper mapper;
    private final OmnichannelProperties props;
    private final Clock clock;

    public ChatbotService(ChatbotRepository chatbots, ChatbotVersionRepository versions, WorkspaceRepository workspaces,
            UserRepository users, FlowParser parser, FlowValidator validator, MongoTemplate mongo, ObjectMapper mapper,
            OmnichannelProperties props, Clock clock) {
        this.chatbots = chatbots;
        this.versions = versions;
        this.workspaces = workspaces;
        this.users = users;
        this.parser = parser;
        this.validator = validator;
        this.mongo = mongo;
        this.mapper = mapper;
        this.props = props;
        this.clock = clock;
    }

    public ChatbotView create(AuthenticatedUser me, String workspaceId, String name) {
        String clean = cleanName(name);
        var ws = workspaces.findById(workspaceId).orElseThrow(() -> new NotFoundException("workspace not found"));
        Chatbot c = new Chatbot();
        c.setCustomerId(ws.getCustomerId());
        c.setWorkspaceId(workspaceId);
        c.setName(clean);
        c.setStatus(ChatbotStatus.DRAFT);
        Map<String, Object> flow = new LinkedHashMap<>();
        flow.put("nodes", List.of());
        flow.put("edges", List.of());
        c.setFlow(flow);
        c.setSettings(ChatbotSettings.defaults());
        c.setVersionNumber(0);
        c.setCreatedBy(me.userId());
        c.setCreationTime(clock.instant());
        c.setLastUpdateTime(c.getCreationTime());
        if (chatbots.existsByWorkspaceIdAndNameIgnoreCase(workspaceId, clean)) throw clash(clean);
        try {
            return view(chatbots.insert(c));
        } catch (DuplicateKeyException e) {
            throw clash(clean);
        }
    }

    public Chatbot require(String workspaceId, String id) {
        return chatbots.findById(id).filter(c -> workspaceId.equals(c.getWorkspaceId()))
                .orElseThrow(() -> new NotFoundException("chatbot not found"));
    }

    public ChatbotView get(String workspaceId, String id) {
        return view(require(workspaceId, id));
    }

    public List<ChatbotSummary> list(String workspaceId) {
        Instant weekAgo = clock.instant().minus(Duration.ofDays(7));
        List<ChatbotSummary> out = new ArrayList<>();
        for (Chatbot c : chatbots.findAllByWorkspaceIdOrderByNameAsc(workspaceId)) {
            Criteria base = Criteria.where("workspaceId").is(workspaceId).and("chatbotId").is(c.getId());
            long runs7d = mongo.count(new Query(Criteria.where("workspaceId").is(workspaceId).and("chatbotId").is(c.getId())
                    .and("createdAt").gte(weekAgo)), ChatbotRun.class);
            long active = mongo.count(new Query(Criteria.where("workspaceId").is(workspaceId).and("chatbotId").is(c.getId())
                    .and("status").in(ChatbotRunStatus.RUNNING, ChatbotRunStatus.WAITING)), ChatbotRun.class);
            ChatbotRun last = mongo.findOne(new Query(base).with(Sort.by(Sort.Direction.DESC, "createdAt")).limit(1),
                    ChatbotRun.class);
            out.add(new ChatbotSummary(c.getId(), c.getName(), c.getStatus(), runs7d, active,
                    last == null ? null : last.getCreatedAt(), c.getLastUpdateTime()));
        }
        return out;
    }

    public SaveResult save(AuthenticatedUser me, String workspaceId, String id, SaveRequest r) {
        Chatbot c = require(workspaceId, id);
        requireDraft(c);
        if (r.version() == null) throw new ValidationException("version", "is required");
        if (!r.version().equals(c.getVersion())) {
            throw new OptimisticLockingFailureException("the chatbot was saved by someone else");
        }
        if (r.name() != null) {
            String clean = cleanName(r.name());
            if (!clean.equalsIgnoreCase(c.getName()) && chatbots.existsByWorkspaceIdAndNameIgnoreCase(workspaceId, clean)) {
                throw clash(clean);
            }
            c.setName(clean);
        }
        if (r.description() != null) c.setDescription(r.description().strip().isEmpty() ? null : r.description().strip());
        Map<String, Object> flowMap = r.flow() == null ? c.getFlow() : r.flow();
        Flow flow = parser.parse(flowMap);
        if (r.settings() != null) c.setSettings(checkSettings(r.settings()));
        Instant now = clock.instant();
        if (graphChanged(c, flowMap)) {
            ChatbotVersion v = new ChatbotVersion();
            v.setWorkspaceId(workspaceId);
            v.setChatbotId(c.getId());
            v.setVersionNumber(c.getVersionNumber() + 1);
            v.setFlow(flowMap);
            v.setSavedAt(now);
            v.setSavedBy(me.userId());
            versions.insert(v);
            c.setVersionNumber(v.getVersionNumber());
            prune(c);
        }
        c.setFlow(flowMap);
        c.setLastUpdateTime(now);
        c.setVersion(r.version());
        Chatbot saved;
        try {
            saved = chatbots.save(c);
        } catch (DuplicateKeyException e) {
            throw clash(c.getName());
        }
        return new SaveResult(view(saved), validator.validate(flow, new ValidationContext(workspaceId, id, false)));
    }

    /** The problems a save would report, for the test start and the publish check. */
    public List<Problem> problems(Chatbot c) {
        return validator.validate(parser.parse(c.getFlow()), new ValidationContext(c.getWorkspaceId(), c.getId(), false));
    }

    public ChatbotView publish(AuthenticatedUser me, String workspaceId, String id) {
        Chatbot c = require(workspaceId, id);
        if (c.getStatus() == ChatbotStatus.PUBLISHED) return view(c);
        Map<String, String> fields = new LinkedHashMap<>();
        for (Problem p : problems(c)) {
            if (p.severity() != Severity.ERROR) continue;
            fields.put(p.nodeId() == null ? "flow." + p.field() : "nodes." + p.nodeId() + "." + p.field(), p.message());
        }
        if (!fields.isEmpty()) throw new ValidationException(fields);
        c.setStatus(ChatbotStatus.PUBLISHED);
        c.setPublishedAt(clock.instant());
        c.setPublishedBy(me.userId());
        c.setLastUpdateTime(c.getPublishedAt());
        return view(chatbots.save(c));
    }

    public ChatbotView unpublish(String workspaceId, String id) {
        Chatbot c = require(workspaceId, id);
        if (c.getStatus() == ChatbotStatus.DRAFT) return view(c);
        c.setStatus(ChatbotStatus.DRAFT);
        c.setLastUpdateTime(clock.instant());
        return view(chatbots.save(c));
    }

    public void delete(String workspaceId, String id) {
        Chatbot c = require(workspaceId, id);
        requireDraft(c);
        versions.deleteAll(versions.findAllByChatbotIdOrderByVersionNumberDesc(id));
        chatbots.delete(c);
    }

    public List<VersionView> versions(String workspaceId, String id) {
        require(workspaceId, id);
        Map<String, String> names = new HashMap<>();
        return versions.findAllByChatbotIdOrderByVersionNumberDesc(id).stream()
                .map(v -> new VersionView(v.getVersionNumber(), v.getSavedAt(), ref(v.getSavedBy(), names))).toList();
    }

    public Map<String, Object> versionFlow(String workspaceId, String id, int n) {
        require(workspaceId, id);
        return versions.findByChatbotIdAndVersionNumber(id, n).map(ChatbotVersion::getFlow)
                .orElseThrow(() -> new NotFoundException("version not found"));
    }

    public SaveResult restore(AuthenticatedUser me, String workspaceId, String id, int n) {
        Chatbot c = require(workspaceId, id);
        Map<String, Object> flow = versionFlow(workspaceId, id, n);
        return save(me, workspaceId, id, new SaveRequest(null, null, flow, null, c.getVersion()));
    }

    // ---- helpers ----

    private void requireDraft(Chatbot c) {
        if (c.getStatus() != ChatbotStatus.DRAFT) {
            throw new CodedException(HttpStatus.CONFLICT, PUBLISHED, null, "Move the chatbot to draft to change it");
        }
    }

    private static String cleanName(String name) {
        String clean = name == null ? "" : name.strip();
        if (clean.isEmpty()) throw new ValidationException("name", "is required");
        if (clean.length() > MAX_NAME) throw new ValidationException("name", "must be at most " + MAX_NAME + " characters");
        return clean;
    }

    private static DuplicateException clash(String name) {
        return new DuplicateException("name", "\"" + name + "\" already exists in this brand");
    }

    static ChatbotSettings checkSettings(ChatbotSettings s) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (s.reminderAfterMinutes() != null && (s.reminderAfterMinutes() < 1 || s.reminderAfterMinutes() > 1440)) {
            errors.put("settings.reminderAfterMinutes", "must be 1 to 1440 minutes");
        }
        if (s.reminderAfterMinutes() != null && (s.reminderMessage() == null || s.reminderMessage().isBlank())) {
            errors.put("settings.reminderMessage", "is required when the reminder is on");
        }
        if (s.endAfterMinutes() < 5 || s.endAfterMinutes() > 2880) errors.put("settings.endAfterMinutes", "must be 5 to 2880 minutes");
        if (s.invalidReplyRetries() < 0 || s.invalidReplyRetries() > 5) errors.put("settings.invalidReplyRetries", "must be 0 to 5");
        if (s.retryMessage() == null || s.retryMessage().isBlank()) errors.put("settings.retryMessage", "is required");
        Map<String, String> texts = new LinkedHashMap<>();
        texts.put("settings.reminderMessage", s.reminderMessage());
        texts.put("settings.endMessage", s.endMessage());
        texts.put("settings.retryMessage", s.retryMessage());
        texts.put("settings.giveUpMessage", s.giveUpMessage());
        for (var e : texts.entrySet()) {
            if (e.getValue() != null && e.getValue().length() > 1024) errors.put(e.getKey(), "must be at most 1024 characters");
        }
        if (!errors.isEmpty()) throw new ValidationException(errors);
        return s;
    }

    /** True when the nodes or edges differ from the newest version's (canvas-only keys ignored). */
    private boolean graphChanged(Chatbot c, Map<String, Object> flow) {
        String now = signature(flow);
        return versions.findByChatbotIdAndVersionNumber(c.getId(), c.getVersionNumber())
                .map(v -> !signature(v.getFlow()).equals(now))
                .orElseGet(() -> !signature(c.getFlow()).equals(now) || !now.equals(signature(Map.of())));
    }

    @SuppressWarnings("unchecked")
    String signature(Map<String, Object> flow) {
        List<Map<String, Object>> nodes = new ArrayList<>();
        Object ns = flow == null ? null : flow.get("nodes");
        if (ns instanceof List<?> l) {
            for (Object o : l) {
                if (!(o instanceof Map<?, ?> m)) continue;
                Map<String, Object> copy = new LinkedHashMap<>();
                for (Map.Entry<?, ?> e : m.entrySet()) if (!CANVAS_ONLY.contains(String.valueOf(e.getKey()))) copy.put(String.valueOf(e.getKey()), e.getValue());
                nodes.add(copy);
            }
        }
        nodes.sort(Comparator.comparing(m -> String.valueOf(m.get("id"))));
        List<Map<String, Object>> edges = new ArrayList<>();
        Object es = flow == null ? null : flow.get("edges");
        if (es instanceof List<?> l) {
            for (Object o : l) {
                if (!(o instanceof Map<?, ?> m)) continue;
                Map<String, Object> copy = new LinkedHashMap<>();
                for (String k : List.of("id", "source", "sourceHandle", "target")) if (m.get(k) != null) copy.put(k, m.get(k));
                edges.add(copy);
            }
        }
        edges.sort(Comparator.comparing(m -> m.get("source") + ">" + m.get("sourceHandle") + ">" + m.get("target")));
        return mapper.writeValueAsString(Map.of("nodes", nodes, "edges", edges));
    }

    /** Keeps the newest {@code versionsKept} versions and any an active run still points to. */
    private void prune(Chatbot c) {
        List<ChatbotVersion> all = versions.findAllByChatbotIdOrderByVersionNumberDesc(c.getId());
        int kept = props.chatbot().versionsKept();
        if (all.size() <= kept) return;
        for (ChatbotVersion v : all.subList(kept, all.size())) {
            boolean inUse = mongo.exists(new Query(Criteria.where("chatbotId").is(c.getId()).and("versionNumber").is(v.getVersionNumber())
                    .and("status").in(ChatbotRunStatus.RUNNING, ChatbotRunStatus.WAITING)), ChatbotRun.class);
            if (!inUse) versions.delete(v);
        }
    }

    ChatbotView view(Chatbot c) {
        Map<String, String> names = new HashMap<>();
        return new ChatbotView(c.getId(), c.getName(), c.getDescription(), c.getStatus(), c.getFlow(), c.getSettings(),
                c.getVersionNumber(), c.getVersion(), ref(c.getCreatedBy(), names), c.getCreationTime(),
                c.getLastUpdateTime(), c.getPublishedAt(), ref(c.getPublishedBy(), names));
    }

    private AutomationRef ref(String userId, Map<String, String> names) {
        if (userId == null) return null;
        String name = names.computeIfAbsent(userId, id -> users.findById(id).map(User::getName).orElse(null));
        return new AutomationRef(userId, name);
    }
}
