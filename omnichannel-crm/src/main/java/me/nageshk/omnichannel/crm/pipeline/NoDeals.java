package me.nageshk.omnichannel.crm.pipeline;

import me.nageshk.omnichannel.crm.access.CrmContext;
import org.springframework.stereotype.Component;

/** Placeholder until deals exist: nothing holds deals. Delete it when the deal service implements {@link DealPlacement}. */
@Component
class NoDeals implements DealPlacement {

    @Override
    public long countInStage(String workspaceId, String pipelineId, String stageId) {
        return 0;
    }

    @Override
    public long countInPipeline(String workspaceId, String pipelineId) {
        return 0;
    }

    @Override
    public void relocateStage(CrmContext ctx, String pipelineId, String fromStageId, String toStageId) {}

    @Override
    public void relocatePipeline(CrmContext ctx, String fromPipelineId, String toPipelineId) {}
}
