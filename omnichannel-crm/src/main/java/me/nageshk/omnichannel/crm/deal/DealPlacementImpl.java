package me.nageshk.omnichannel.crm.deal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.nageshk.omnichannel.common.api.NotFoundException;
import me.nageshk.omnichannel.crm.access.CrmContext;
import me.nageshk.omnichannel.crm.pipeline.CrmPipeline;
import me.nageshk.omnichannel.crm.pipeline.CrmStage;
import me.nageshk.omnichannel.crm.pipeline.DealPlacement;
import me.nageshk.omnichannel.crm.pipeline.StageType;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

/**
 * Deal counts and relocation for pipeline editing. Relocated deals are appended to their target lane in their old
 * order with the stage-entry rules of a move, each lane batch under the {@link LaneLocks} of the target and source
 * lanes; each records {@code STAGE_CHANGED} (plus {@code DEAL_WON}/{@code DEAL_LOST} when it closes). A deal that
 * changed meanwhile (or is gone) is skipped. The counters of their contacts and companies are recomputed once.
 */
@Component
class DealPlacementImpl implements DealPlacement {

    private final MongoTemplate mongo;
    private final Placement placement;
    private final LaneLocks lanes;

    DealPlacementImpl(MongoTemplate mongo, Placement placement, LaneLocks lanes) {
        this.mongo = mongo;
        this.placement = placement;
        this.lanes = lanes;
    }

    @Override
    public long countInStage(String workspaceId, String pipelineId, String stageId) {
        return mongo.count(new Query(Placement.lane(workspaceId, pipelineId, stageId)), CrmDeal.class);
    }

    @Override
    public long countInPipeline(String workspaceId, String pipelineId) {
        return mongo.count(new Query(Criteria.where("workspaceId").is(workspaceId).and("pipelineId").is(pipelineId)),
                CrmDeal.class);
    }

    /** The pipeline as stored (it is mid-change: the lanes are rewritten after this). */
    @Override
    public void relocateStage(CrmContext ctx, String pipelineId, String fromStageId, String toStageId) {
        if (fromStageId.equals(toStageId)) return;
        CrmPipeline pipeline = pipeline(ctx, pipelineId);
        CrmStage target = Placement.stage(pipeline, toStageId)
                .orElseThrow(() -> new NotFoundException("Stage not found"));
        Moved moved = new Moved();
        lanes.withLanes(ctx.workspaceId(), List.of(new LaneLocks.Lane(pipelineId, fromStageId),
                new LaneLocks.Lane(pipelineId, toStageId)), () -> {
                    List<CrmDeal> moving = mongo.find(new Query(Placement.lane(ctx.workspaceId(), pipelineId,
                            fromStageId)).with(Sort.by(Sort.Order.asc("position"), Sort.Order.asc("_id"))),
                            CrmDeal.class);
                    append(ctx, moving, pipeline, pipeline, target, "when the stage was removed", moved);
                    return null;
                });
        placement.recompute(ctx.workspaceId(), moved.contacts, moved.companies);
    }

    /**
     * WON deals go to the target's first WON lane and LOST ones to its first LOST lane, keeping {@code closedAt}
     * and {@code lostReason}; OPEN deals (and closed ones when the target has no lane of that type) to its first
     * OPEN lane. Their activities are re-tagged with the target pipeline.
     */
    @Override
    public void relocatePipeline(CrmContext ctx, String fromPipelineId, String toPipelineId) {
        CrmPipeline from = pipeline(ctx, fromPipelineId);
        CrmPipeline to = pipeline(ctx, toPipelineId);
        CrmStage open = Placement.first(to, StageType.OPEN)
                .orElseThrow(() -> new NotFoundException("The target pipeline has no OPEN stage"));
        Map<DealStatus, CrmStage> targets = new EnumMap<>(DealStatus.class);
        targets.put(DealStatus.OPEN, open);
        targets.put(DealStatus.WON, Placement.first(to, StageType.WON).orElse(open));
        targets.put(DealStatus.LOST, Placement.first(to, StageType.LOST).orElse(open));
        Map<String, Integer> rank = new HashMap<>();
        List<CrmStage> fromLanes = Placement.ordered(from);
        for (int i = 0; i < fromLanes.size(); i++) rank.put(fromLanes.get(i).getId(), i);
        List<CrmDeal> all = new ArrayList<>(mongo.find(new Query(Criteria.where("workspaceId")
                .is(ctx.workspaceId()).and("pipelineId").is(fromPipelineId)), CrmDeal.class));
        all.sort(Comparator.<CrmDeal>comparingInt(d -> rank.getOrDefault(d.getStageId(), Integer.MAX_VALUE))
                .thenComparingDouble(CrmDeal::getPosition).thenComparing(CrmDeal::getId));
        // one batch per target lane, in the old order
        Map<String, List<CrmDeal>> byTarget = new LinkedHashMap<>();
        Map<String, CrmStage> stageOf = new HashMap<>();
        for (CrmDeal d : all) {
            CrmStage t = targets.get(d.getStatus() == null ? DealStatus.OPEN : d.getStatus());
            byTarget.computeIfAbsent(t.getId(), k -> new ArrayList<>()).add(d);
            stageOf.put(t.getId(), t);
        }
        Moved moved = new Moved();
        byTarget.forEach((stageId, batch) -> {
            Set<LaneLocks.Lane> locked = new LinkedHashSet<>();
            locked.add(new LaneLocks.Lane(toPipelineId, stageId));
            batch.forEach(d -> locked.add(new LaneLocks.Lane(fromPipelineId, d.getStageId())));
            lanes.withLanes(ctx.workspaceId(), locked, () -> {
                append(ctx, batch, from, to, stageOf.get(stageId), "when the pipeline was deleted", moved);
                return null;
            });
        });
        placement.retag(ctx.workspaceId(), moved.dealIds, toPipelineId);
        placement.recompute(ctx.workspaceId(), moved.contacts, moved.companies);
    }

    /** What a relocation moved: the deals and the contacts and companies whose counters follow. */
    private static final class Moved {
        final List<String> dealIds = new ArrayList<>();
        final Set<String> contacts = new LinkedHashSet<>();
        final Set<String> companies = new LinkedHashSet<>();
    }

    /** Appends {@code moving} to {@code target} in order; the caller holds the lanes' locks. */
    private void append(CrmContext ctx, List<CrmDeal> moving, CrmPipeline from, CrmPipeline to, CrmStage target,
            String why, Moved moved) {
        if (moving.isEmpty()) return;
        double position = placement.appendPosition(ctx.workspaceId(), to.getId(), target.getId(), null)
                - Positions.GAP;
        for (CrmDeal d : moving) {
            position += Positions.GAP;
            CrmDeal saved = placement.tryPlace(ctx, d, from, to, target, position, null, null, why);
            if (saved == null) continue; // changed or deleted meanwhile
            moved.dealIds.add(saved.getId());
            moved.contacts.addAll(Placement.contactsOf(saved));
            moved.companies.add(saved.getCompanyId());
        }
    }

    private CrmPipeline pipeline(CrmContext ctx, String id) {
        CrmPipeline p = mongo.findOne(new Query(Criteria.where("_id").is(id).and("workspaceId").is(ctx.workspaceId())),
                CrmPipeline.class);
        if (p == null) throw new NotFoundException("Pipeline not found");
        return p;
    }
}
