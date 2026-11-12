package me.nageshk.omnichannel.service.automation;

import java.util.LinkedHashMap;
import java.util.Map;

/** Whether an Add to CRM's pipeline and lane can be used, shared by rules and chatbot nodes. */
public final class CrmTargets {

    private CrmTargets() {}

    /** Keys {@code pipelineId}, {@code stageId}; empty when usable. */
    public static Map<String, String> check(AutomationCrm.CrmOptions options, String pipelineId, String stageId) {
        Map<String, String> out = new LinkedHashMap<>();
        if (!options.enabled()) {
            out.put("pipelineId", "CRM is off for this brand");
            return out;
        }
        String id = pipelineId == null || pipelineId.isBlank() ? options.defaultPipelineId() : pipelineId;
        if (id == null) {
            out.put("pipelineId", "The brand has no default pipeline");
            return out;
        }
        var pipeline = options.pipelines().stream().filter(p -> p.id().equals(id)).findFirst().orElse(null);
        if (pipeline == null) {
            out.put("pipelineId", "The pipeline no longer exists");
        } else if (!pipeline.active()) {
            out.put("pipelineId", "The pipeline is not active");
        } else if (stageId != null && !stageId.isBlank() && pipeline.lanes().stream().noneMatch(l -> l.id().equals(stageId))) {
            out.put("stageId", "The lane is not in the pipeline");
        }
        return out;
    }
}
