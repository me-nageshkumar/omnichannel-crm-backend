package me.nageshk.omnichannel.crm.pipeline;

import me.nageshk.omnichannel.crm.access.CrmContext;

/** Where the deals sit, as far as pipeline editing needs to know; implemented by the deal service. */
public interface DealPlacement {

    long countInStage(String workspaceId, String pipelineId, String stageId);

    long countInPipeline(String workspaceId, String pipelineId);

    /** Moves every deal in {@code fromStageId} to {@code toStageId} of the same pipeline. */
    void relocateStage(CrmContext ctx, String pipelineId, String fromStageId, String toStageId);

    /** Moves every deal of one pipeline to the first OPEN stage of another. */
    void relocatePipeline(CrmContext ctx, String fromPipelineId, String toPipelineId);
}
