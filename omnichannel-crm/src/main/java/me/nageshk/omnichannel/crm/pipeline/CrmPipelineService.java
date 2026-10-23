package me.nageshk.omnichannel.crm.pipeline;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import me.nageshk.omnichannel.common.api.NotFoundException;
import me.nageshk.omnichannel.common.api.ValidationException;
import me.nageshk.omnichannel.crm.access.CrmContext;
import me.nageshk.omnichannel.crm.activity.ActivityLog;
import me.nageshk.omnichannel.crm.activity.ActivityType;
import me.nageshk.omnichannel.crm.common.CrmTarget;
import me.nageshk.omnichannel.crm.common.TargetType;
import me.nageshk.omnichannel.crm.access.PipelineAccessService;
import me.nageshk.omnichannel.crm.common.CrmErrors;
import me.nageshk.omnichannel.crm.pipeline.dto.PipelineRequest;
import me.nageshk.omnichannel.crm.pipeline.dto.StageRequest;
import me.nageshk.omnichannel.crm.settings.CrmDefaults;
import me.nageshk.omnichannel.crm.settings.CrmSettingsService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

@Service
public class CrmPipelineService {

    /** The seeded pipeline's _id is derived from the brand, so two first requests racing collide and exactly one insert wins. */
    private static final String SEED_ID_PREFIX = "seed-sales-";
    private static final String ROTTING_RULE = "must be between 1 and 365, or 0 for none";

    private final CrmPipelineRepository repository;
    private final MongoTemplate mongo;
    private final CrmSettingsService settings;
    private final DealPlacement deals;
    private final PipelineAccessService access;
    private final ActivityLog activities;

    CrmPipelineService(CrmPipelineRepository repository, MongoTemplate mongo, CrmSettingsService settings,
            DealPlacement deals, PipelineAccessService access,
            ActivityLog activities) {
        this.activities = activities;
        this.access = access;
        this.repository = repository;
        this.mongo = mongo;
        this.settings = settings;
        this.deals = deals;
    }

    /**
     * Makes sure the brand has a default pipeline and that the settings point at it: with no pipeline at all "Sales"
     * is created; otherwise the oldest active one (any, if none is active) becomes the default. Safe against
     * concurrent first requests.
     */
    public CrmPipeline ensureDefault(String workspaceId, String customerId, String byUserId) {
        Optional<CrmPipeline> def = Optional.ofNullable(mongo.findOne(
                new Query(inBrand(workspaceId).and("isDefault").is(true)), CrmPipeline.class));
        CrmPipeline chosen;
        if (def.isPresent()) {
            chosen = def.get();
        } else {
            List<CrmPipeline> all = oldestFirst(workspaceId);
            if (!all.isEmpty()) {
                chosen = all.stream().filter(CrmPipeline::isActive).findFirst().orElse(all.get(0));
                mongo.updateFirst(new Query(Criteria.where("_id").is(chosen.getId()).and("workspaceId").is(workspaceId)),
                        new Update().set("isDefault", true).set("lastModifiedBy", byUserId)
                                .set("lastUpdateTime", Instant.now()).inc("version", 1),
                        CrmPipeline.class);
            } else {
                chosen = seed(workspaceId, customerId, byUserId);
            }
        }
        settings.setDefaultPipeline(workspaceId, chosen.getId(), byUserId);
        return chosen;
    }

    private CrmPipeline seed(String workspaceId, String customerId, String byUserId) {
        CrmPipeline p = base(workspaceId, customerId, byUserId);
        p.setId(SEED_ID_PREFIX + workspaceId);
        p.setName("Sales");
        p.setIsDefault(true);
        p.setStages(salesLanes());
        try {
            return repository.insert(p);
        } catch (DuplicateKeyException e) {
            return repository.findById(p.getId()).orElseThrow(() -> e);
        }
    }

    private static List<CrmStage> salesLanes() {
        return CrmDefaults.salesStages().stream().map(spec -> {
            CrmStage s = new CrmStage();
            s.setId(UUID.randomUUID().toString());
            s.setName(spec.name());
            s.setColor(spec.color());
            s.setPosition(spec.position());
            s.setProbability(spec.probability());
            s.setType(StageType.valueOf(spec.type()));
            return s;
        }).toList();
    }

    private static CrmPipeline base(String workspaceId, String customerId, String byUserId) {
        CrmPipeline p = new CrmPipeline();
        p.setWorkspaceId(workspaceId);
        p.setCustomerId(customerId);
        p.setCreatedBy(byUserId);
        p.setLastModifiedBy(byUserId);
        return p;
    }

    /** The pipelines the caller may see, by name. */
    public List<CrmPipeline> list(CrmContext ctx) {
        Query q = new Query(inBrand(ctx.workspaceId()));
        if (!ctx.pipelines().all()) q.addCriteria(ctx.pipelines().criteria("_id"));
        return mongo.find(q, CrmPipeline.class).stream()
                .sorted(Comparator.comparing(p -> p.getName().toLowerCase())).toList();
    }

    /** @throws NotFoundException (404) when missing or outside the caller's access */
    public CrmPipeline get(CrmContext ctx, String id) {
        if (!ctx.pipelines().allows(id)) throw new NotFoundException("Pipeline not found");
        CrmPipeline p = mongo.findOne(new Query(inBrand(ctx.workspaceId()).and("_id").is(id)), CrmPipeline.class);
        if (p == null) throw new NotFoundException("Pipeline not found");
        return p;
    }

    public CrmPipeline create(CrmContext ctx, PipelineRequest req) {
        ctx.require("crm:settings");
        String name = requireName(req.name());
        validateRotting(req.rottingDays());
        Integer max = ctx.settings().getMaxPipelines();
        if (max != null && repository.countByWorkspaceId(ctx.workspaceId()) >= max) {
            throw CrmErrors.conflict("LIMIT_REACHED", "The brand may have at most " + max + " pipelines",
                    Map.of("limit", String.valueOf(max)));
        }
        List<CrmStage> lanes = req.stages() == null
                ? salesLanes() : StageRules.validate(req.stages(), List.of(), id -> 0).stages();
        boolean makeDefault = Boolean.TRUE.equals(req.isDefault());
        boolean active = req.active() == null || req.active();
        if (makeDefault && !active) throw new ValidationException("active", "an inactive pipeline cannot be the default");
        CrmPipeline p = base(ctx.workspaceId(), ctx.customerId(), ctx.userId());
        p.setName(name);
        p.setActive(active);
        p.setRottingDays(req.rottingDays() == null || req.rottingDays() == 0 ? null : req.rottingDays());
        p.setStages(lanes);
        p.setIsDefault(makeDefault);
        CrmPipeline saved = repository.insert(p);
        if (makeDefault) promote(ctx, saved.getId());
        changed(ctx, saved.getId(), "Created pipeline " + saved.getName());
        return saved;
    }

    public CrmPipeline update(CrmContext ctx, String id, PipelineRequest req) {
        ctx.require("crm:settings");
        CrmPipeline current = get(ctx, id);
        Map<String, String> errors = new java.util.LinkedHashMap<>();
        if (req.version() == null) errors.put("version", "is required");
        if (req.name() == null || req.name().isBlank()) errors.put("name", "must not be blank");
        else if (req.name().trim().length() > 100) errors.put("name", "must be at most 100 characters");
        if (req.rottingDays() != null && (req.rottingDays() < 0 || req.rottingDays() > 365))
            errors.put("rottingDays", ROTTING_RULE);
        boolean wasDefault = Boolean.TRUE.equals(current.getIsDefault());
        boolean active = req.active() == null ? current.isActive() : req.active();
        boolean isDefault = req.isDefault() == null ? wasDefault : req.isDefault();
        if (wasDefault && !isDefault) errors.put("isDefault", "make another pipeline the default instead");
        if (isDefault && !active)
            errors.put("active", wasDefault ? "the default pipeline cannot be deactivated; make another the default first"
                    : "an inactive pipeline cannot be the default");
        if (!errors.isEmpty()) throw new ValidationException(errors);

        Update u = new Update().set("name", req.name().trim()).set("active", active).set("isDefault", isDefault);
        // absent (or null) keeps the stored value; 0 switches rotting off for this pipeline
        if (req.rottingDays() != null) {
            if (req.rottingDays() == 0) u.unset("rottingDays");
            else u.set("rottingDays", req.rottingDays());
        }
        CrmPipeline saved = guarded(ctx, id, req.version(), u);
        if (isDefault && !wasDefault) promote(ctx, id);
        changed(ctx, id, "Updated pipeline " + saved.getName());
        return saved;
    }

    public CrmPipeline replaceStages(CrmContext ctx, String id, List<StageRequest> requested, Long version) {
        ctx.require("crm:settings");
        CrmPipeline current = get(ctx, id);
        if (version == null) throw new ValidationException("version", "is required");
        if (!version.equals(current.getVersion())) throw versionConflict();
        StageRules.Plan plan = StageRules.validate(requested, current.getStages(),
                stageId -> deals.countInStage(ctx.workspaceId(), id, stageId));
        // deals leave a removed lane before the lane goes, so a failed relocation can simply be retried
        plan.moves().forEach((from, to) -> deals.relocateStage(ctx, id, from, to));
        CrmPipeline saved = guarded(ctx, id, version, new Update().set("stages", plan.stages()));
        changed(ctx, id, "Changed lanes of " + saved.getName());
        return saved;
    }

    public void delete(CrmContext ctx, String id, String moveDealsToPipelineId) {
        ctx.require("crm:settings");
        CrmPipeline pipeline = get(ctx, id);
        boolean hasTarget = moveDealsToPipelineId != null && !moveDealsToPipelineId.isBlank();
        if (hasTarget) {
            if (moveDealsToPipelineId.equals(id)) throw new ValidationException("moveDealsToPipelineId", "must be another pipeline");
            if (!ctx.pipelines().allows(moveDealsToPipelineId)) throw new NotFoundException("Target pipeline not found");
            CrmPipeline target = mongo.findOne(
                    new Query(inBrand(ctx.workspaceId()).and("_id").is(moveDealsToPipelineId)), CrmPipeline.class);
            if (target == null) throw new NotFoundException("Target pipeline not found");
            if (!target.isActive()) throw new ValidationException("moveDealsToPipelineId", "must be an active pipeline");
        }
        long held = deals.countInPipeline(ctx.workspaceId(), id);
        if (held > 0 && !hasTarget) {
            throw CrmErrors.conflict("PIPELINE_HAS_DEALS", "The pipeline holds deals; give a moveDealsToPipelineId",
                    Map.of("count", String.valueOf(held)));
        }
        if (held > 0) deals.relocatePipeline(ctx, id, moveDealsToPipelineId);
        mongo.remove(new Query(inBrand(ctx.workspaceId()).and("_id").is(id)), CrmPipeline.class);
        access.removePipeline(ctx.workspaceId(), id);
        boolean wasDefault = Boolean.TRUE.equals(pipeline.getIsDefault());
        if (wasDefault || id.equals(ctx.settings().getDefaultPipelineId())) {
            List<CrmPipeline> rest = oldestFirst(ctx.workspaceId());
            Optional<CrmPipeline> next = rest.stream().filter(CrmPipeline::isActive).findFirst()
                    .or(() -> rest.stream().findFirst());
            if (next.isPresent()) {
                mongo.updateFirst(new Query(inBrand(ctx.workspaceId()).and("_id").is(next.get().getId())),
                        new Update().set("isDefault", true).set("lastModifiedBy", ctx.userId())
                                .set("lastUpdateTime", Instant.now()).inc("version", 1),
                        CrmPipeline.class);
            }
            settings.setDefaultPipeline(ctx.workspaceId(), next.map(CrmPipeline::getId).orElse(null), ctx.userId());
        }
        changed(ctx, id, "Deleted pipeline " + pipeline.getName());
    }

    /** Makes an accessible, active pipeline the only default and points the settings at it. */
    public void makeDefault(CrmContext ctx, String id) {
        ctx.require("crm:settings");
        CrmPipeline p = get(ctx, id);
        if (!p.isActive()) throw new ValidationException("defaultPipelineId", "must be an active pipeline");
        promote(ctx, id);
        changed(ctx, id, "Made " + p.getName() + " the default pipeline");
    }

    private void changed(CrmContext ctx, String pipelineId, String summary) {
        activities.record(ctx, ActivityType.SETTINGS_CHANGED, new CrmTarget(TargetType.PIPELINE, pipelineId),
                List.of(), null, null, summary, new java.util.LinkedHashMap<>(Map.of("pipelineId", pipelineId)));
    }

    /** Makes {@code id} the only default and points the settings at it. */
    private void promote(CrmContext ctx, String id) {
        mongo.updateMulti(new Query(inBrand(ctx.workspaceId()).and("isDefault").is(true).and("_id").ne(id)),
                new Update().set("isDefault", false).set("lastModifiedBy", ctx.userId())
                        .set("lastUpdateTime", Instant.now()).inc("version", 1),
                CrmPipeline.class);
        mongo.updateFirst(new Query(inBrand(ctx.workspaceId()).and("_id").is(id)), new Update().set("isDefault", true),
                CrmPipeline.class);
        settings.setDefaultPipeline(ctx.workspaceId(), id, ctx.userId());
    }

    private CrmPipeline guarded(CrmContext ctx, String id, long version, Update update) {
        update.set("lastModifiedBy", ctx.userId()).set("lastUpdateTime", Instant.now()).inc("version", 1);
        CrmPipeline saved = mongo.findAndModify(
                new Query(inBrand(ctx.workspaceId()).and("_id").is(id).and("version").is(version)), update,
                FindAndModifyOptions.options().returnNew(true), CrmPipeline.class);
        if (saved != null) return saved;
        if (!mongo.exists(new Query(inBrand(ctx.workspaceId()).and("_id").is(id)), CrmPipeline.class))
            throw new NotFoundException("Pipeline not found");
        throw versionConflict();
    }

    private static RuntimeException versionConflict() {
        return CrmErrors.conflict("VERSION_CONFLICT", "The pipeline was changed by someone else; reload and retry", Map.of());
    }

    private List<CrmPipeline> oldestFirst(String workspaceId) {
        return mongo.find(new Query(inBrand(workspaceId)).with(Sort.by("creationTime", "_id")), CrmPipeline.class);
    }

    private static Criteria inBrand(String workspaceId) {
        return Criteria.where("workspaceId").is(workspaceId);
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank()) throw new ValidationException("name", "must not be blank");
        if (name.trim().length() > 100) throw new ValidationException("name", "must be at most 100 characters");
        return name.trim();
    }

    private static void validateRotting(Integer days) {
        if (days != null && (days < 0 || days > 365)) throw new ValidationException("rottingDays", ROTTING_RULE);
    }
}
