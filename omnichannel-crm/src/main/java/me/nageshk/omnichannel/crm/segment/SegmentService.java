package me.nageshk.omnichannel.crm.segment;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import me.nageshk.omnichannel.common.api.CodedException;
import me.nageshk.omnichannel.common.api.NotFoundException;
import me.nageshk.omnichannel.crm.access.CrmAccess;
import me.nageshk.omnichannel.crm.access.CrmContext;
import me.nageshk.omnichannel.crm.common.CrmErrors;
import me.nageshk.omnichannel.crm.platform.CampaignDirectory;
import me.nageshk.omnichannel.crm.platform.CampaignRef;
import me.nageshk.omnichannel.crm.platform.ChannelAccount;
import me.nageshk.omnichannel.crm.platform.ChannelContactQuery;
import me.nageshk.omnichannel.crm.segment.SegmentScope.Flag;
import me.nageshk.omnichannel.crm.segment.dto.SegmentCatalogView;
import me.nageshk.omnichannel.crm.segment.dto.SegmentPreviewView;
import me.nageshk.omnichannel.crm.segment.dto.SegmentRequest;
import me.nageshk.omnichannel.crm.segment.dto.SegmentView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/** Saved segments (spec D C3, C6–C10): CRUD, the per-caller preview and catalogue, and the cached brand-wide counts. */
@Service
public class SegmentService {

    private static final Logger log = LoggerFactory.getLogger(SegmentService.class);
    static final int MAX_NAME = 80;
    static final int MAX_DESCRIPTION = 500;
    static final String AUTHORITY = "crm:segments";

    private final CrmSegmentRepository repository;
    private final MongoTemplate mongo;
    private final SegmentResolver resolver;
    private final SegmentCatalog catalog;
    private final CampaignDirectory campaigns;
    private final ChannelContactQuery channel;
    private final CrmAccess access;
    private final Clock clock;

    public SegmentService(CrmSegmentRepository repository, MongoTemplate mongo, SegmentResolver resolver,
            SegmentCatalog catalog, CampaignDirectory campaigns, ChannelContactQuery channel, CrmAccess access,
            Clock clock) {
        this.repository = repository;
        this.mongo = mongo;
        this.resolver = resolver;
        this.catalog = catalog;
        this.campaigns = campaigns;
        this.channel = channel;
        this.access = access;
        this.clock = clock;
    }

    public List<SegmentView> list(CrmContext ctx) {
        ctx.require(AUTHORITY);
        return repository.findByWorkspaceIdOrderByName(ctx.workspaceId()).stream().map(s -> view(s, null)).toList();
    }

    public SegmentView get(CrmContext ctx, String id) {
        ctx.require(AUTHORITY);
        return view(require(ctx, id), resolver.scopeFor(ctx));
    }

    public SegmentCatalogView fields(CrmContext ctx) {
        ctx.require(AUTHORITY);
        return catalog.build(ctx);
    }

    public SegmentView create(CrmContext ctx, SegmentRequest req) {
        ctx.require(AUTHORITY);
        SegmentScope scope = resolver.scopeFor(ctx);
        validate(req, scope, false);
        String name = req.name().trim();
        if (repository.findByWorkspaceIdAndNameKey(ctx.workspaceId(), nameKey(name)).isPresent()) throw SegmentErrors.nameTaken();
        CrmSegment s = new CrmSegment();
        s.setWorkspaceId(ctx.workspaceId());
        s.setCustomerId(ctx.customerId());
        s.setName(name);
        s.setNameKey(nameKey(name));
        s.setDescription(req.description() == null ? null : req.description().trim());
        s.setRules(req.rules());
        s.setCreatedBy(ctx.userId());
        s.setLastModifiedBy(ctx.userId());
        try {
            s = repository.insert(s);
        } catch (DuplicateKeyException raced) {
            throw SegmentErrors.nameTaken();
        }
        refreshCountsQuietly(ctx, s);
        return view(require(ctx, s.getId()), scope);
    }

    public SegmentView update(CrmContext ctx, String id, SegmentRequest req) {
        ctx.require(AUTHORITY);
        CrmSegment s = require(ctx, id);
        SegmentScope scope = resolver.scopeFor(ctx);
        validate(req, scope, true);
        if (!req.version().equals(s.getVersion())) throw versionConflict();
        String name = req.name().trim();
        Optional<CrmSegment> same = repository.findByWorkspaceIdAndNameKey(ctx.workspaceId(), nameKey(name));
        if (same.isPresent() && !same.get().getId().equals(id)) throw SegmentErrors.nameTaken();
        s.setName(name);
        s.setNameKey(nameKey(name));
        s.setDescription(req.description() == null ? null : req.description().trim());
        s.setRules(req.rules());
        s.setLastModifiedBy(ctx.userId());
        try {
            s = repository.save(s);
        } catch (OptimisticLockingFailureException stale) {
            throw versionConflict();
        } catch (DuplicateKeyException raced) {
            throw SegmentErrors.nameTaken();
        }
        refreshCountsQuietly(ctx, s);
        return view(require(ctx, id), scope);
    }

    public void delete(CrmContext ctx, String id) {
        ctx.require(AUTHORITY);
        CrmSegment s = require(ctx, id);
        List<CampaignRef> using = campaigns.usingSegment(ctx.workspaceId(), id);
        if (!using.isEmpty()) throw SegmentErrors.inUse(using);
        try {
            repository.delete(s);
        } catch (OptimisticLockingFailureException stale) {
            throw versionConflict();
        }
    }

    public SegmentPreviewView preview(CrmContext ctx, SegmentRuleGroup rules) {
        ctx.require(AUTHORITY);
        SegmentScope scope = resolver.scopeFor(ctx);
        SegmentValidator.validate(rules, scope.known());
        return previewOf(ctx, resolver.resolve(ctx, rules, scope));
    }

    public SegmentPreviewView preview(CrmContext ctx, String id) {
        ctx.require(AUTHORITY);
        CrmSegment s = require(ctx, id);
        return previewOf(ctx, resolver.resolve(ctx, s.getRules()));
    }

    /** Every segment of the brand, counted over the brand-wide universe (spec C9); a failing one is logged and skipped. */
    public void refreshCounts(String workspaceId) {
        List<CrmSegment> all = repository.findByWorkspaceIdOrderByName(workspaceId);
        if (all.isEmpty()) return;
        CrmContext system = access.forSystem(workspaceId, all.get(0).getCustomerId());
        SegmentScope scope = resolver.scopeFor(system);
        for (CrmSegment s : all) {
            try {
                refreshCounts(system, s, scope);
            } catch (RuntimeException e) {
                log.warn("segment {} of brand {} could not be counted: {}", s.getId(), workspaceId, e.toString());
            }
        }
    }

    /** A targeted write of {@code cachedCounts}; the segment's version is left alone. */
    public void refreshCounts(CrmContext system, CrmSegment s, SegmentScope scope) {
        SegmentMembers m = resolver.resolve(system, s.getRules(), scope);
        Map<String, Long> perAccount = new LinkedHashMap<>();
        for (SegmentMember member : m.members()) {
            perAccount.merge(member.channelAccountId() == null ? "" : member.channelAccountId(), 1L, Long::sum);
        }
        List<CachedCounts.ChannelCount> counts = perAccount.entrySet().stream()
                .map(e -> new CachedCounts.ChannelCount(e.getKey().isEmpty() ? null : e.getKey(), e.getValue())).toList();
        CachedCounts cached = new CachedCounts(m.total(), m.people(), m.truncated(), counts, Instant.now(clock));
        // by collection name: naming the entity class would bump its @Version on this bookkeeping write
        mongo.updateFirst(new Query(Criteria.where("_id").is(new org.bson.types.ObjectId(s.getId()))
                .and("workspaceId").is(s.getWorkspaceId())), new Update().set("cachedCounts", cached), "crmSegments");
    }

    private void refreshCountsQuietly(CrmContext ctx, CrmSegment s) {
        try {
            CrmContext system = access.forSystem(ctx.workspaceId(), ctx.customerId());
            refreshCounts(system, s, resolver.scopeFor(system));
        } catch (RuntimeException e) {
            log.warn("segment {} saved but not counted: {}", s.getId(), e.toString());
        }
    }

    private SegmentPreviewView previewOf(CrmContext ctx, SegmentMembers m) {
        Map<String, ChannelAccount> accounts = channel.accounts(ctx.workspaceId()).stream()
                .collect(Collectors.toMap(ChannelAccount::id, Function.identity()));
        Map<String, Long> perAccount = new LinkedHashMap<>();
        for (SegmentMember member : m.members()) {
            perAccount.merge(member.channelAccountId() == null ? "" : member.channelAccountId(), 1L, Long::sum);
        }
        List<SegmentPreviewView.ChannelAccountCount> rows = new ArrayList<>();
        perAccount.forEach((id, n) -> {
            ChannelAccount a = accounts.get(id);
            rows.add(new SegmentPreviewView.ChannelAccountCount(id.isEmpty() ? null : id, a == null ? null : a.kind(),
                    a == null ? (id.isEmpty() ? "No channel account" : "Removed account") : a.label(), n));
        });
        rows.sort((x, y) -> Long.compare(y.count(), x.count()));
        return new SegmentPreviewView(m.total(), m.people(), m.truncated(), rows);
    }

    /** Name and description shape together with the rules' shape, so one answer names every problem. */
    private static void validate(SegmentRequest req, SegmentScope scope, boolean update) {
        Map<String, String> errors = new LinkedHashMap<>();
        String name = req.name() == null ? "" : req.name().trim();
        if (name.isEmpty() || name.length() > MAX_NAME) errors.put("name", "must be 1 to " + MAX_NAME + " characters");
        if (req.description() != null && req.description().trim().length() > MAX_DESCRIPTION) {
            errors.put("description", "must be at most " + MAX_DESCRIPTION + " characters");
        }
        if (update && req.version() == null) errors.put("version", "is required");
        try {
            SegmentValidator.validate(req.rules(), scope.known());
        } catch (CodedException e) {
            if (!SegmentErrors.INVALID.equals(e.code())) throw e;
            errors.putAll(e.extra());
        }
        if (!errors.isEmpty()) throw SegmentErrors.invalid(errors);
    }

    static String nameKey(String name) {
        return name.trim().toLowerCase(Locale.ROOT);
    }

    static RuntimeException versionConflict() {
        return CrmErrors.conflict("VERSION_CONFLICT", "The segment was changed by someone else; reload and retry",
                Map.of());
    }

    private CrmSegment require(CrmContext ctx, String id) {
        return repository.findByIdAndWorkspaceId(id, ctx.workspaceId())
                .orElseThrow(() -> new NotFoundException("Segment not found"));
    }

    /** The view; with a scope, every rule is annotated for the caller. */
    private static SegmentView view(CrmSegment s, SegmentScope scope) {
        return new SegmentView(s.getId(), s.getName(), s.getDescription(), annotate(s.getRules(), scope),
                s.getCachedCounts(), s.getLastSentAt(), s.getLastCampaignId(), s.getLastCampaignName(),
                s.getCreationTime(), s.getLastUpdateTime(), s.getVersion());
    }

    static Map<String, Object> annotate(SegmentRuleGroup g, SegmentScope scope) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (g == null) return out;
        out.put("combinator", g.combinator());
        out.put("not", g.not());
        List<Object> rules = new ArrayList<>();
        for (SegmentRuleNode n : g.rules()) {
            if (n instanceof SegmentRuleGroup child) {
                rules.add(annotate(child, scope));
            } else if (n instanceof SegmentRule r) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("field", r.field());
                m.put("operator", r.operator());
                m.put("value", r.value());
                if (scope != null) {
                    Flag flag = scope.flag(r, g);
                    if (flag == Flag.MISSING) m.put("missing", true);
                    if (flag == Flag.INACCESSIBLE) m.put("inaccessible", true);
                }
                rules.add(m);
            }
        }
        out.put("rules", rules);
        return out;
    }
}
