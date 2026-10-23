package me.nageshk.omnichannel.crm.pipeline;

import lombok.Getter;
import lombok.Setter;

/** One lane of a pipeline. */
@Getter
@Setter
public class CrmStage {

    private String id;
    private String name;
    private String color;
    private double position;
    private int probability;
    private StageType type;
    /** Null = no work-in-progress limit. */
    private Integer wipLimit;
}
