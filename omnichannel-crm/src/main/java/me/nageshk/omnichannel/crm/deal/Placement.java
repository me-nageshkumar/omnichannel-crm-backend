package me.nageshk.omnichannel.crm.deal;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import me.nageshk.omnichannel.common.api.NotFoundException;
import me.nageshk.omnichannel.crm.access.CrmContext;
import me.nageshk.omnichannel.crm.activity.ActivityLog;
import me.nageshk.omnichannel.crm.activity.ActivityType;
import me.nageshk.omnichannel.crm.common.CrmTarget;
import me.nageshk.omnichannel.crm.common.TargetType;
import me.nageshk.omnichannel.crm.company.CompanyCounters;
import me.nageshk.omnichannel.crm.contact.ContactCounters;
import me.nageshk.omnichannel.crm.pipeline.CrmPipeline;
import me.nageshk.omnichannel.crm.pipeline.CrmStage;
import me.nageshk.omnichannel.crm.pipeline.StageType;
import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.BulkOperations;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/**
 * Where deals sit: lane positions (spec 5.4), the rules of entering a stage (status from its type, {@code closedAt}
 * on closing and cleared with {@code lostReason} on reopening, the stage's probability unless overridden), the
 * activities a move records and the derived counters it refreshes. Shared by the deal service and the pipeline
 * relocation. The position methods ({@link #appendPosition}, {@link #slot}, {@link #place}, {@link #settle},
 * {@link #respace}) must run under the {@link LaneLocks} of every lane they touch.
 */
@Component
class Placement {

    static final String DEALS = "crmDeals";
    private static final String ACTIVITIES = "crmActivities";
    private static final int RESPACE_ATTEMPTS = 3;

    private final MongoTemplate mongo;
    private final ActivityLog activities;
    private final ContactCounters contactCounters;
    private final CompanyCounters companyCounters;
    private final Clock clock;

    Placement(MongoTemplate mongo, ActivityLog activities, ContactCounters contactCounters,
            CompanyCounters companyCounters, Clock clock) {
        this.mongo = mongo;
        this.activities = activities;
        this.contactCounters = contactCounters;
        this.companyCounters = companyCounters;
        this.clock = clock;
    }

    // --- positions -------------------------------------------------------------------------------------

    /** After the lane's last card (ignoring {@code excludeId}). */
    double appendPosition(String ws, String pipelineId, String stageId, String excludeId) {
        return Positions.between(edge(ws, pipelineId, stageId, excludeId, null, true).orElse(null), null)
                .getAsDouble();
    }

    /**
     * The position for {@code movingId} directly after {@code beforeId} and ahead of {@code afterId} (validated
     * cards of the lane, either may be null). A missing side is taken from the lane, so a single neighbour still
     * means "adjacent to it"; a slot too narrow re-spaces the lane (one bulk write) and is computed once more.
     */
    double slot(String ws, String pipelineId, String stageId, String movingId, String beforeId, String afterId) {
        for (int attempt = 0; attempt < 2; attempt++) {
            Double low;
            Double high;
            if (beforeId == null && afterId == null) {
                low = edge(ws, pipelineId, stageId, movingId, null, true).orElse(null);
                high = null;
            } else if (beforeId != null) {
                low = positionOf(ws, beforeId);
                Double given = afterId == null ? null : positionOf(ws, afterId);
                // a stale "after" (no longer below "before") is ignored: the card goes straight after "before"
                high = given != null && given > low ? given
                        : edge(ws, pipelineId, stageId, movingId, Criteria.where("position").gt(low), false)
                                .orElse(null);
            } else {
                high = positionOf(ws, afterId);
                low = edge(ws, pipelineId, stageId, movingId, Criteria.where("position").lt(high), true).orElse(null);
            }
            OptionalDouble p = Positions.between(low, high);
            if (p.isPresent()) return p.getAsDouble();
            respace(ws, pipelineId, stageId, movingId);
        }
        throw new IllegalStateException("no slot in lane " + stageId + " after re-spacing");
    }

    /**
     * After a card was written at {@code position}: when another card of the lane sits within {@link
     * Positions#MIN_GAP} of it (two moves into the same gap), the lane is re-spaced in its current order.
     */
    void settle(String ws, String pipelineId, String stageId, String dealId, double position) {
        Query crowded = new Query(lane(ws, pipelineId, stageId).and("_id").ne(dealId).and("position")
                .gt(position - Positions.MIN_GAP).lt(position + Positions.MIN_GAP));
        if (mongo.exists(crowded, DEALS)) respace(ws, pipelineId, stageId, null);
    }

    /**
     * Rewrites the lane to {@code GAP·(i+1)} in its current order (position, then id), leaving out {@code excludeId};
     * plain updates, no version bump. Repeated (bounded) while a concurrent write left two cards on one position.
     */
    void respace(String ws, String pipelineId, String stageId, String excludeId) {
        for (int attempt = 0; attempt < RESPACE_ATTEMPTS; attempt++) {
            Query q = new Query(lane(ws, pipelineId, stageId))
                    .with(Sort.by(Sort.Order.asc("position"), Sort.Order.asc("_id")));
            q.fields().include("_id");
            List<Object> ids = new ArrayList<>();
            for (Document d : mongo.find(q, Document.class, DEALS)) {
                if (excludeId == null || !excludeId.equals(d.get("_id").toString())) ids.add(d.get("_id"));
            }
            if (ids.isEmpty()) return;
            List<Double> positions = Positions.respaced(ids.size());
            BulkOperations bulk = mongo.bulkOps(BulkOperations.BulkMode.UNORDERED, DEALS);
            for (int i = 0; i < ids.size(); i++) {
                // only while the card is still in the lane: a card that left keeps the position it got there
                bulk.updateOne(new Query(lane(ws, pipelineId, stageId).and("_id").is(ids.get(i))),
                        new Update().set("position", positions.get(i)));
            }
            bulk.execute();
            if (!hasDuplicates(ws, pipelineId, stageId)) return;
        }
    }

    private boolean hasDuplicates(String ws, String pipelineId, String stageId) {
        Query q = new Query(lane(ws, pipelineId, stageId)).with(Sort.by(Sort.Order.asc("position")));
        q.fields().include("position");
        Double previous = null;
        for (Document d : mongo.find(q, Document.class, DEALS)) {
            double p = d.get("position") instanceof Number n ? n.doubleValue() : 0;
            if (previous != null && p - previous < Positions.MIN_GAP) return true;
            previous = p;
        }
        return false;
    }

    /** The largest ({@code last}) or smallest position of the lane matching {@code extra}, ignoring a card. */
    private Optional<Double> edge(String ws, String pipelineId, String stageId, String excludeId, Criteria extra,
            boolean last) {
        Criteria c = lane(ws, pipelineId, stageId);
        if (excludeId != null) c = c.and("_id").ne(excludeId);
        Query q = new Query(extra == null ? c : new Criteria().andOperator(c, extra))
                .with(Sort.by(last ? Sort.Direction.DESC : Sort.Direction.ASC, "position")).limit(1);
        q.fields().include("position");
        Document d = mongo.findOne(q, Document.class, DEALS);
        return d == null || !(d.get("position") instanceof Number n) ? Optional.empty() : Optional.of(n.doubleValue());
    }

    private double positionOf(String ws, String dealId) {
        Query q = new Query(Criteria.where("_id").is(dealId).and("workspaceId").is(ws));
        q.fields().include("position");
        Document d = mongo.findOne(q, Document.class, DEALS);
        if (d == null) throw new NotFoundException("Deal not found");
        return d.get("position") instanceof Number n ? n.doubleValue() : 0;
    }

    static Criteria lane(String ws, String pipelineId, String stageId) {
        return Criteria.where("workspaceId").is(ws).and("pipelineId").is(pipelineId).and("stageId").is(stageId);
    }

    // --- stages ----------------------------------------------------------------------------------------

    static DealStatus statusOf(CrmStage stage) {
        return stage.getType() == StageType.WON ? DealStatus.WON
                : stage.getType() == StageType.LOST ? DealStatus.LOST : DealStatus.OPEN;
    }

    static List<CrmStage> ordered(CrmPipeline p) {
        List<CrmStage> stages = new ArrayList<>(p.getStages() == null ? List.of() : p.getStages());
        stages.sort(Comparator.comparingDouble(CrmStage::getPosition));
        return stages;
    }

    static Optional<CrmStage> first(CrmPipeline p, StageType type) {
        return ordered(p).stream().filter(s -> s.getType() == type).findFirst();
    }

    static Optional<CrmStage> stage(CrmPipeline p, String stageId) {
        if (p == null || p.getStages() == null || stageId == null) return Optional.empty();
        return p.getStages().stream().filter(s -> stageId.equals(s.getId())).findFirst();
    }

    /**
     * Adds to {@code u} what entering {@code target} of {@code toPipelineId} at {@code position} changes: status from
     * the stage type; a new stage restarts {@code stageEnteredAt} and takes the stage's probability unless
     * {@code overridden}; OPEN clears {@code closedAt} and {@code lostReason}; closing sets {@code closedAt} (kept
     * when already closed the same way); WON clears {@code lostReason}.
     */
    static void enter(Update u, CrmDeal current, String toPipelineId, CrmStage target, double position,
            boolean overridden, Instant now) {
        DealStatus status = statusOf(target);
        boolean moved = !target.getId().equals(current.getStageId())
                || !toPipelineId.equals(current.getPipelineId());
        u.set("pipelineId", toPipelineId).set("stageId", target.getId()).set("position", position)
                .set("status", status);
        if (moved) {
            u.set("stageEnteredAt", now);
            if (!overridden) u.set("probability", target.getProbability());
        }
        if (status == DealStatus.OPEN) {
            u.unset("closedAt").unset("lostReason");
        } else {
            if (status != current.getStatus() || current.getClosedAt() == null) u.set("closedAt", now);
            if (status == DealStatus.WON) u.unset("lostReason");
        }
    }

    /**
     * Puts the deal into {@code target} of {@code to} at {@code position} with {@link #enter}'s rules, records the
     * move and re-spaces the lane if two cards collide. A LOST {@code outcome} stores {@code lostReason}; without one
     * a deal entering LOST has none and an already LOST deal keeps its reason. Counters are left to the caller.
     *
     * @throws NotFoundException when the deal is gone
     * @throws me.nageshk.omnichannel.common.api.CodedException 409 VERSION_CONFLICT when its pipeline, stage or
     *     status changed since {@code deal} was read
     */
    CrmDeal place(CrmContext ctx, CrmDeal deal, CrmPipeline from, CrmPipeline to, CrmStage target, double position,
            DealStatus outcome, String lostReason, String why) {
        CrmDeal saved = tryPlace(ctx, deal, from, to, target, position, outcome, lostReason, why);
        if (saved != null) return saved;
        if (mongo.exists(new Query(Criteria.where("_id").is(deal.getId()).and("workspaceId").is(ctx.workspaceId())),
                DEALS)) throw DealService.versionConflict();
        throw new NotFoundException("Deal not found");
    }

    /**
     * {@link #place} as one update conditional on the deal still sitting where {@code deal} was read (pipeline,
     * stage and status), so it never undoes a concurrent move or touches a deal the caller no longer sees.
     *
     * @return the saved deal, or null when that condition (or the deal) is gone
     */
    CrmDeal tryPlace(CrmContext ctx, CrmDeal deal, CrmPipeline from, CrmPipeline to, CrmStage target,
            double position, DealStatus outcome, String lostReason, String why) {
        Instant now = Instant.now(clock);
        Update u = new Update();
        enter(u, deal, to.getId(), target, position, deal.isProbabilityOverridden(), now);
        if (outcome == DealStatus.LOST && statusOf(target) == DealStatus.LOST) {
            if (lostReason != null) u.set("lostReason", lostReason);
            else if (deal.getStatus() != DealStatus.LOST) u.unset("lostReason");
        }
        u.set("lastModifiedBy", ctx.userId()).set("lastUpdateTime", now).inc("version", 1);
        CrmDeal saved = mongo.findAndModify(new Query(Criteria.where("_id").is(deal.getId())
                .and("workspaceId").is(ctx.workspaceId()).and("pipelineId").is(deal.getPipelineId())
                .and("stageId").is(deal.getStageId()).and("status").is(deal.getStatus())), u,
                FindAndModifyOptions.options().returnNew(true), CrmDeal.class);
        if (saved == null) return null;
        recordMove(ctx, deal, saved, from, to, why);
        settle(ctx.workspaceId(), saved.getPipelineId(), saved.getStageId(), saved.getId(), saved.getPosition());
        return saved;
    }

    /** {@code STAGE_CHANGED} when the stage or pipeline changed; {@code DEAL_WON}/{@code DEAL_LOST} on closing. */
    void recordMove(CrmContext ctx, CrmDeal before, CrmDeal saved, CrmPipeline from, CrmPipeline to, String why) {
        boolean pipelineChanged = !Objects.equals(before.getPipelineId(), saved.getPipelineId());
        if (pipelineChanged || !Objects.equals(before.getStageId(), saved.getStageId())) {
            String fromName = stage(from, before.getStageId()).map(CrmStage::getName).orElse(null);
            String toName = stage(to, saved.getStageId()).map(CrmStage::getName).orElse(saved.getStageId());
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("fromStageId", before.getStageId());
            payload.put("toStageId", saved.getStageId());
            payload.put("fromStageName", fromName);
            payload.put("toStageName", toName);
            String summary;
            String shownFrom = fromName == null ? "a removed stage" : fromName;
            if (pipelineChanged) {
                payload.put("fromPipelineId", before.getPipelineId());
                payload.put("toPipelineId", saved.getPipelineId());
                payload.put("toPipelineName", to.getName());
                summary = "Moved from " + shownFrom + " (" + (from == null ? before.getPipelineId() : from.getName())
                        + ") to " + toName + " (" + to.getName() + ")";
            } else {
                summary = "Moved from " + shownFrom + " to " + toName;
            }
            activities.record(ctx, ActivityType.STAGE_CHANGED, target(saved), related(saved), saved.getPipelineId(),
                    phoneOf(saved), why == null ? summary : summary + " " + why, payload);
        }
        if (saved.getStatus() != before.getStatus()) recordOutcome(ctx, saved);
    }

    /** {@code DEAL_WON}/{@code DEAL_LOST} for a deal that is (now) closed; nothing for an OPEN one. */
    void recordOutcome(CrmContext ctx, CrmDeal saved) {
        if (saved.getStatus() != DealStatus.OPEN) {
            boolean won = saved.getStatus() == DealStatus.WON;
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("stageId", saved.getStageId());
            payload.put("amount", plain(saved.getAmount()));
            payload.put("currency", saved.getCurrency());
            payload.put("lostReason", saved.getLostReason());
            activities.record(ctx, won ? ActivityType.DEAL_WON : ActivityType.DEAL_LOST, target(saved),
                    related(saved), saved.getPipelineId(), phoneOf(saved),
                    won ? "Deal won" : "Deal lost" + (saved.getLostReason() == null ? "" : ": " + saved.getLostReason()),
                    payload);
        }
    }

    /**
     * Re-tags the deals' activities with the pipeline they now sit in, so restricted users' timelines follow the
     * deal (an entry of a deal moved into a hidden pipeline is hidden too).
     */
    void retag(String ws, Collection<String> dealIds, String pipelineId) {
        if (dealIds.isEmpty()) return;
        mongo.updateMulti(new Query(Criteria.where("workspaceId").is(ws).and("target.type")
                .is(TargetType.DEAL.name()).and("target.id").in(dealIds)), new Update().set("pipelineId", pipelineId),
                ACTIVITIES);
    }

    // --- derived values --------------------------------------------------------------------------------

    /** Recomputes the contacts' and companies' deal figures (null and blank ids are skipped, each once). */
    void recompute(String ws, Collection<String> contactIds, Collection<String> companyIds) {
        new LinkedHashSet<>(contactIds).stream().filter(id -> id != null && !id.isBlank())
                .forEach(id -> contactCounters.recompute(ws, id));
        new LinkedHashSet<>(companyIds).stream().filter(id -> id != null && !id.isBlank())
                .forEach(id -> companyCounters.recompute(ws, id));
    }

    /** The primary and every linked contact. */
    static Set<String> contactsOf(CrmDeal d) {
        Set<String> ids = new LinkedHashSet<>();
        if (d.getPrimaryCrmContactId() != null) ids.add(d.getPrimaryCrmContactId());
        if (d.getCrmContactIds() != null) d.getCrmContactIds().stream().filter(Objects::nonNull).forEach(ids::add);
        return ids;
    }

    static CrmTarget target(CrmDeal d) {
        return new CrmTarget(TargetType.DEAL, d.getId());
    }

    /** The deal's contacts and company: their timelines show the entry and their {@code lastActivityAt} follows. */
    static List<CrmTarget> related(CrmDeal d) {
        List<CrmTarget> out = new ArrayList<>();
        contactsOf(d).forEach(id -> out.add(new CrmTarget(TargetType.CONTACT, id)));
        if (d.getCompanyId() != null) out.add(new CrmTarget(TargetType.COMPANY, d.getCompanyId()));
        return out;
    }

    static String phoneOf(CrmDeal d) {
        return d.getChannel() == null ? null : d.getChannel().phoneNumberId();
    }

    static Object plain(Object v) {
        return v instanceof BigDecimal b ? b.toPlainString() : v;
    }
}
