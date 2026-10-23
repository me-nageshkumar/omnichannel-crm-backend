package me.nageshk.omnichannel.crm.pipeline.dto;

import java.util.List;
import me.nageshk.omnichannel.crm.pipeline.CrmPipeline;
import me.nageshk.omnichannel.crm.pipeline.StageType;

public record PipelineView(String id, String name, boolean isDefault, boolean active, Integer rottingDays,
        List<Stage> stages, Long version) {

    public record Stage(String id, String name, String color, double position, int probability, StageType type,
            Integer wipLimit) {}

    public static PipelineView of(CrmPipeline p) {
        return new PipelineView(p.getId(), p.getName(), Boolean.TRUE.equals(p.getIsDefault()), p.isActive(),
                p.getRottingDays(),
                p.getStages().stream().map(s -> new Stage(s.getId(), s.getName(), s.getColor(), s.getPosition(),
                        s.getProbability(), s.getType(), s.getWipLimit())).toList(),
                p.getVersion());
    }
}
