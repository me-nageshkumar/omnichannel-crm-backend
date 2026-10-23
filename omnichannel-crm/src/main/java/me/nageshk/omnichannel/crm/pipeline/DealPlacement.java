package me.nageshk.omnichannel.crm.pipeline;

import me.nageshk.omnichannel.crm.access.CrmContext;

/** Where the deals sit, as far as pipeline editing needs to know; implemented by the deal service. */
public interface DealPlacement {

    long countInStage(String workspaceId, String pipelineId, String stageId);

    long countInPipeline(String workspaceId, String pipelineId);

    /** Moves every deal in {@code fromStageId} to {@code toStageId} of the same pipeline. */
    void relocateStage(CrmContext ctx, String pipelineId, String fromStageId, String toStageId);

    /**
     * Moves every deal of one pipeline into another: OPEN deals to its first OPEN stage, WON and LOST deals to its
     * first WON or LOST stage (staying closed; the first OPEN stage when it has none of that type).
     */
    void relocatePipeline(CrmContext ctx, String fromPipelineId, String toPipelineId);
}
