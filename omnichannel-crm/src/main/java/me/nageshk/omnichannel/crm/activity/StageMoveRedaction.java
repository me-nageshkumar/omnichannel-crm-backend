package me.nageshk.omnichannel.crm.activity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.nageshk.omnichannel.crm.access.PipelineAccess;
import me.nageshk.omnichannel.crm.activity.dto.ActivityView;

/**
 * A stage move as a caller may read it (spec F C19): the side of the move that lies in a pipeline outside their access
 * loses its keys, and the summary, which names both sides, is rewritten. A deal's whole history follows it into a new
 * pipeline, so a side counts as hidden by its pipeline id or, for entries that carry none, by its stage id.
 */
final class StageMoveRedaction {

    private static final String STAGE_CHANGED = ActivityType.STAGE_CHANGED.name();
    private static final List<String> FROM_KEYS = List.of("fromPipelineId", "fromStageId", "fromStageName",
            "fromStageEnteredAt");
    private static final List<String> TO_KEYS = List.of("toPipelineId", "toStageId", "toStageName", "toPipelineName");

    private StageMoveRedaction() {}

    /** @param hiddenStageIds the stages of the pipelines outside {@code access} */
    static ActivityView redact(ActivityView a, PipelineAccess access, Set<String> hiddenStageIds) {
        if (access.all() || !STAGE_CHANGED.equals(a.type()) || a.payload() == null) return a;
        Map<String, Object> p = a.payload();
        boolean fromHidden = hidden(p.get("fromPipelineId"), p.get("fromStageId"), access, hiddenStageIds);
        boolean toHidden = hidden(p.get("toPipelineId"), p.get("toStageId"), access, hiddenStageIds);
        if (!fromHidden && !toHidden) return a;
        Map<String, Object> kept = new LinkedHashMap<>(p);
        if (fromHidden) FROM_KEYS.forEach(kept::remove);
        if (toHidden) TO_KEYS.forEach(kept::remove);
        String summary = fromHidden && toHidden ? "Moved within another pipeline"
                : fromHidden ? into(p) : "Moved from " + name(p.get("fromStageName")) + " to another pipeline";
        return new ActivityView(a.id(), a.type(), a.target(), summary, kept, a.actor(), a.actorType(), a.happenedAt());
    }

    private static boolean hidden(Object pipelineId, Object stageId, PipelineAccess access, Set<String> hiddenStageIds) {
        if (pipelineId instanceof String id && !access.allows(id)) return true;
        return stageId instanceof String id && hiddenStageIds.contains(id);
    }

    private static String into(Map<String, Object> p) {
        String pipeline = p.get("toPipelineName") instanceof String n ? " (" + n + ")" : "";
        return "Moved into " + name(p.get("toStageName")) + pipeline + " from another pipeline";
    }

    private static String name(Object stageName) {
        return stageName instanceof String n ? n : "a stage";
    }
}
