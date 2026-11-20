package me.nageshk.omnichannel.meta.template;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import me.nageshk.omnichannel.common.api.NotFoundException;
import me.nageshk.omnichannel.common.api.ValidationException;
import me.nageshk.omnichannel.meta.account.CloudAccountRepository;
import me.nageshk.omnichannel.meta.api.CloudTemplateInfo;
import me.nageshk.omnichannel.meta.api.CloudTemplates;
import me.nageshk.omnichannel.meta.api.ConnectionStatus;
import me.nageshk.omnichannel.meta.connection.MetaConnection;
import me.nageshk.omnichannel.meta.connection.MetaConnectionRepository;
import me.nageshk.omnichannel.meta.connection.MetaConnectionService;
import me.nageshk.omnichannel.meta.graph.GraphClient;
import me.nageshk.omnichannel.meta.graph.GraphException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Meta templates imported from the brands' WABAs and kept current (spec 2026-11-20 §5.6). */
@Service
public class CloudTemplateService implements CloudTemplates {

    private static final Logger log = LoggerFactory.getLogger(CloudTemplateService.class);
    static final String FIELDS = "id,name,language,status,category,components,parameter_format,rejected_reason";
    private static final TypeReference<List<Map<String, Object>>> LIST = new TypeReference<>() {};

    /** Counts of a sync. */
    public record SyncResult(int added, int updated, int removed) {}

    private final CloudTemplateRepository repository;
    private final MetaConnectionRepository connectionRepository;
    private final MetaConnectionService connections;
    private final CloudAccountRepository accounts;
    private final GraphClient graph;
    private final ObjectMapper mapper;
    private final MongoTemplate mongo;
    private final Clock clock;

    public CloudTemplateService(CloudTemplateRepository repository, MetaConnectionRepository connectionRepository,
            MetaConnectionService connections, CloudAccountRepository accounts, GraphClient graph, ObjectMapper mapper,
            MongoTemplate mongo, Clock clock) {
        this.repository = repository;
        this.connectionRepository = connectionRepository;
        this.connections = connections;
        this.accounts = accounts;
        this.graph = graph;
        this.mapper = mapper;
        this.mongo = mongo;
        this.clock = clock;
    }

    // ---- sync ----

    /** Imports every template of the brand's connections (or one), all pages; missing ones become DELETED. */
    public SyncResult sync(String workspaceId, String connectionId) {
        List<MetaConnection> list = connectionId == null || connectionId.isBlank()
                ? connectionRepository.findByWorkspaceIdOrderByName(workspaceId)
                : List.of(connections.require(workspaceId, connectionId));
        int added = 0, updated = 0, removed = 0;
        for (MetaConnection c : list) {
            if (c.getStatus() != ConnectionStatus.OK) continue;
            SyncResult r = sync(c);
            added += r.added();
            updated += r.updated();
            removed += r.removed();
        }
        return new SyncResult(added, updated, removed);
    }

    SyncResult sync(MetaConnection c) {
        String token = connections.open(c).accessToken();
        int added = 0, updated = 0, removed = 0;
        for (String waba : wabasOf(c)) {
            Set<String> seen = new HashSet<>();
            try {
                JsonNode page = graph.get(token, waba + "/message_templates", Map.of("fields", FIELDS, "limit", "100"));
                int guard = 0;
                while (page != null && guard++ < 50) {
                    for (JsonNode t : page.path("data")) {
                        String metaId = t.path("id").asString("");
                        if (metaId.isEmpty()) continue;
                        seen.add(metaId);
                        if (upsert(c, waba, t)) added++;
                        else updated++;
                    }
                    String next = page.path("paging").path("next").asString("");
                    page = next.isBlank() ? null : graph.getUrl(token, next);
                }
            } catch (GraphException e) {
                log.info("could not import the templates of WABA {}: {}", waba, e.getMessage());
                continue;
            }
            for (CloudTemplate t : repository.findByWorkspaceIdAndWabaIdOrderByNameAscLanguageAsc(c.getWorkspaceId(), waba)) {
                if (!seen.contains(t.getMetaTemplateId()) && !CloudTemplate.DELETED.equals(t.getStatus())) {
                    t.setStatus(CloudTemplate.DELETED);
                    t.setSyncedAt(clock.instant());
                    repository.save(t);
                    removed++;
                }
            }
        }
        return new SyncResult(added, updated, removed);
    }

    /** The WABAs of the connection's numbers, and any the token listed. */
    private List<String> wabasOf(MetaConnection c) {
        Set<String> ids = new java.util.LinkedHashSet<>(c.getWabaIds());
        accounts.findByConnectionId(c.getId()).forEach(a -> ids.add(a.getWabaId()));
        return new ArrayList<>(ids);
    }

    /** True when the template was new. */
    private boolean upsert(MetaConnection c, String waba, JsonNode t) {
        String metaId = t.path("id").asString();
        Optional<CloudTemplate> found = repository.findByWorkspaceIdAndWabaIdAndMetaTemplateId(c.getWorkspaceId(), waba,
                metaId);
        CloudTemplate row = found.orElseGet(CloudTemplate::new);
        row.setWorkspaceId(c.getWorkspaceId());
        row.setConnectionId(c.getId());
        row.setWabaId(waba);
        row.setMetaTemplateId(metaId);
        row.setName(t.path("name").asString(null));
        row.setLanguage(t.path("language").asString(null));
        row.setCategory(t.path("category").asString(null));
        row.setStatus(t.path("status").asString(null));
        String reason = t.path("rejected_reason").asString(null);
        row.setRejectedReason(reason == null || "NONE".equals(reason) ? null : reason);
        row.setParameterFormat(t.path("parameter_format").asString("POSITIONAL"));
        JsonNode components = t.path("components");
        row.setComponents(components.isArray() ? mapper.convertValue(components, LIST) : new ArrayList<>());
        TemplateVariables.Derived d = TemplateVariables.derive(components, row.getParameterFormat(), row.getCategory());
        row.setVariables(d.variables().stream()
                .map(v -> new CloudTemplateInfo.Variable(v.key(), v.component(), v.index(), v.example())).toList());
        row.setHeaderFormat(d.headerFormat());
        row.setSupported(d.supported());
        row.setUnsupportedReason(d.unsupportedReason());
        row.setSyncedAt(clock.instant());
        // a blank the template no longer has loses its fill
        Set<String> keys = new HashSet<>();
        d.variables().forEach(v -> keys.add(v.key()));
        row.setFills(new ArrayList<>(row.getFills().stream().filter(f -> keys.contains(f.key())).toList()));
        repository.save(row);
        return found.isEmpty();
    }

    /** A connection whose token Meta just accepted imports its templates (best effort). */
    @EventListener
    void onChecked(MetaConnectionService.ConnectionChecked e) {
        try {
            connectionRepository.findById(e.connectionId()).ifPresent(this::sync);
        } catch (RuntimeException ex) {
            log.info("could not import templates after connection {} was checked: {}", e.connectionId(), ex.toString());
        }
    }

    /**
     * Meta's {@code message_template_status_update}: the status (and a rejection's reason) of a known template; an
     * unknown template id imports the connection's templates.
     */
    public void applyChange(MetaConnection c, JsonNode value) {
        String metaId = value.path("message_template_id").asString("");
        String event = value.path("event").asString("");
        Optional<CloudTemplate> row = metaId.isEmpty() ? Optional.empty()
                : repository.findFirstByConnectionIdAndMetaTemplateId(c.getId(), metaId);
        if (row.isEmpty()) {
            sync(c);
            return;
        }
        CloudTemplate t = row.get();
        if (!event.isEmpty()) t.setStatus(event);
        String reason = value.path("reason").asString(null);
        t.setRejectedReason(reason == null || "NONE".equals(reason) ? null : reason);
        t.setSyncedAt(clock.instant());
        repository.save(t);
    }

    // ---- reads and fills ----

    public List<CloudTemplateInfo> list(String workspaceId, String phoneNumberId, boolean sendableOnly) {
        List<CloudTemplate> rows;
        if (phoneNumberId == null || phoneNumberId.isBlank()) {
            rows = repository.findByWorkspaceIdOrderByNameAscLanguageAsc(workspaceId);
        } else {
            String waba = accounts.findByPhoneNumberId(phoneNumberId).filter(a -> workspaceId.equals(a.getWorkspaceId()))
                    .map(a -> a.getWabaId()).orElse(null);
            rows = waba == null ? List.of() : repository.findByWorkspaceIdAndWabaIdOrderByNameAscLanguageAsc(workspaceId, waba);
        }
        return rows.stream().map(this::info).filter(i -> !sendableOnly || i.sendable()).toList();
    }

    @Override
    public List<CloudTemplateInfo> forNumber(String workspaceId, String phoneNumberId, boolean sendableOnly) {
        return list(workspaceId, phoneNumberId, sendableOnly);
    }

    @Override
    public CloudTemplateInfo get(String workspaceId, String id) {
        return info(repository.findByIdAndWorkspaceId(id, workspaceId)
                .orElseThrow(() -> new NotFoundException("WhatsApp template not found")));
    }

    public CloudTemplateInfo saveFills(String workspaceId, String id, Map<String, CloudTemplateInfo.Fill> fills) {
        CloudTemplate t = repository.findByIdAndWorkspaceId(id, workspaceId)
                .orElseThrow(() -> new NotFoundException("WhatsApp template not found"));
        Set<String> keys = new HashSet<>();
        t.getVariables().forEach(v -> keys.add(v.key()));
        Map<String, String> errors = new LinkedHashMap<>();
        List<CloudTemplate.FillEntry> entries = new ArrayList<>();
        (fills == null ? Map.<String, CloudTemplateInfo.Fill>of() : fills).forEach((key, f) -> {
            if (!keys.contains(key)) {
                errors.put("fills." + key, "the template has no such blank");
            } else if (f == null || f.kind() == null) {
                errors.put("fills." + key, "kind is required");
            } else if (f.kind() == CloudTemplateInfo.FillKind.CONTACT_FIELD
                    && !"name".equals(f.attributeKey()) && !"phone".equals(f.attributeKey())) {
                errors.put("fills." + key, "a contact field is name or phone");
            } else if (f.kind() == CloudTemplateInfo.FillKind.CONTACT_ATTRIBUTE
                    && (f.attributeKey() == null || f.attributeKey().isBlank())) {
                errors.put("fills." + key, "attributeKey is required");
            } else {
                entries.add(new CloudTemplate.FillEntry(key, f.kind(), f.attributeKey(), f.fallback()));
            }
        });
        if (!errors.isEmpty()) throw new ValidationException(errors);
        t.setFills(entries);
        return info(repository.save(t));
    }

    // ---- sending ----

    @Override
    public List<String> missing(CloudTemplateInfo t, Map<String, String> values) {
        List<String> out = new ArrayList<>();
        for (CloudTemplateInfo.Variable v : t.variables()) {
            String value = values == null ? null : values.get(v.key());
            if (value == null || value.isBlank()) out.add(v.key());
        }
        return out;
    }

    @Override
    public Built build(CloudTemplateInfo t, Map<String, String> values, String headerMetaMediaId) {
        TemplateVariables.Built b = TemplateVariables.build(t.name(), t.language(), t.components(), t.parameterFormat(),
                values, headerMetaMediaId);
        return new Built(b.template(), b.renderedBody(), b.snapshot());
    }

    @Override
    public void markUsed(String id) {
        mongo.updateFirst(new Query(Criteria.where("_id").is(id)), new Update().set("lastUsedAt", clock.instant()),
                CloudTemplate.class);
    }

    private CloudTemplateInfo info(CloudTemplate t) {
        Map<String, CloudTemplateInfo.Fill> fills = new LinkedHashMap<>();
        t.getFills().forEach(f -> fills.put(f.key(), new CloudTemplateInfo.Fill(f.kind(), f.attributeKey(), f.fallback())));
        boolean sendable = "APPROVED".equals(t.getStatus()) && t.isSupported();
        return new CloudTemplateInfo(t.getId(), t.getConnectionId(), t.getWabaId(), t.getName(), t.getLanguage(),
                t.getCategory(), t.getStatus(), t.getRejectedReason(), t.getParameterFormat(),
                mapper.valueToTree(t.getComponents()), new CloudTemplateInfo.Header(t.getHeaderFormat()),
                t.getVariables(), fills, t.isSupported(), t.getUnsupportedReason(), sendable, t.getSyncedAt(),
                t.getLastUsedAt());
    }
}
