package me.nageshk.omnichannel.crm.pipeline;

import lombok.Getter;
import lombok.Setter;
import org.springframework.data.mongodb.core.mapping.Field;

/** One lane of a pipeline. */
@Getter
@Setter
public class CrmStage {

    /** Kept as {@code id} in the document (an embedded {@code id} would otherwise be stored as {@code _id}). */
    @Field("id")
    private String id;
    private String name;
    private String color;
    private double position;
    private int probability;
    private StageType type;
    /** Null = no work-in-progress limit. */
    private Integer wipLimit;
}
