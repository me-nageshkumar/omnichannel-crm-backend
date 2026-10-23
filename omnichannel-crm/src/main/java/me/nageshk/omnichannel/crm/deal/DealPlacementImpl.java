package me.nageshk.omnichannel.crm.deal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
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
 * Deal counts and relocation for pipeline editing. Relocated deals are appended to the target lane in their old order
 * with the stage-entry rules of a move, and each records {@code STAGE_CHANGED} (plus {@code DEAL_WON}/{@code
 * DEAL_LOST} when it closes); the counters of their contacts and companies are recomputed once.
 */
@Component
class DealPlacementImpl implements DealPlacement {

    private final MongoTemplate mongo;
    private final Placement placement;

    DealPlacementImpl(MongoTemplate mongo, Placement placement) {
        this.mongo = mongo;
        this.placement = placement;
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
        List<CrmDeal> moving = mongo.find(new Query(Placement.lane(ctx.workspaceId(), pipelineId, fromStageId))
                .with(Sort.by(Sort.Order.asc("position"), Sort.Order.asc("_id"))), CrmDeal.class);
        relocate(ctx, moving, pipeline, pipeline, target, "when the stage was removed");
    }

    @Override
    public void relocatePipeline(CrmContext ctx, String fromPipelineId, String toPipelineId) {
        CrmPipeline from = pipeline(ctx, fromPipelineId);
        CrmPipeline to = pipeline(ctx, toPipelineId);
        CrmStage target = Placement.first(to, StageType.OPEN)
                .orElseThrow(() -> new NotFoundException("The target pipeline has no OPEN stage"));
        Map<String, Integer> rank = new HashMap<>();
        List<CrmStage> lanes = Placement.ordered(from);
        for (int i = 0; i < lanes.size(); i++) rank.put(lanes.get(i).getId(), i);
        List<CrmDeal> moving = new ArrayList<>(mongo.find(new Query(Criteria.where("workspaceId")
                .is(ctx.workspaceId()).and("pipelineId").is(fromPipelineId)), CrmDeal.class));
        moving.sort(Comparator.<CrmDeal>comparingInt(d -> rank.getOrDefault(d.getStageId(), Integer.MAX_VALUE))
                .thenComparingDouble(CrmDeal::getPosition).thenComparing(CrmDeal::getId));
        relocate(ctx, moving, from, to, target, "when the pipeline was deleted");
    }

    private void relocate(CrmContext ctx, List<CrmDeal> moving, CrmPipeline from, CrmPipeline to, CrmStage target,
            String why) {
        if (moving.isEmpty()) return;
        double position = placement.appendPosition(ctx.workspaceId(), to.getId(), target.getId(), null)
                - Positions.GAP;
        Set<String> contacts = new LinkedHashSet<>();
        Set<String> companies = new LinkedHashSet<>();
        for (CrmDeal d : moving) {
            position += Positions.GAP;
            CrmDeal saved;
            try {
                saved = placement.place(ctx, d, from, to, target, position, null, null, why);
            } catch (NotFoundException gone) {
                continue; // deleted meanwhile
            }
            contacts.addAll(Placement.contactsOf(saved));
            companies.add(saved.getCompanyId());
        }
        placement.recompute(ctx.workspaceId(), contacts, companies);
    }

    private CrmPipeline pipeline(CrmContext ctx, String id) {
        CrmPipeline p = mongo.findOne(new Query(Criteria.where("_id").is(id).and("workspaceId").is(ctx.workspaceId())),
                CrmPipeline.class);
        if (p == null) throw new NotFoundException("Pipeline not found");
        return p;
    }
}
