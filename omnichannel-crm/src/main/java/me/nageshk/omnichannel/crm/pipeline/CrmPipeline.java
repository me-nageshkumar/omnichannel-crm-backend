package me.nageshk.omnichannel.crm.pipeline;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

/** A sales pipeline of a brand: ordered lanes that deals move through. */
@Document("crmPipelines")
@CompoundIndex(name = "ws_default", def = "{'workspaceId': 1, 'isDefault': 1}")
@Getter
@Setter
public class CrmPipeline {

    @Id
    private String id;
    private String workspaceId;
    private String customerId;
    private String name;
    private Boolean isDefault = false;
    /** Inactive pipelines stay visible but are not offered for new deals. */
    private boolean active = true;
    /** Null = the settings default. */
    private Integer rottingDays;
    private List<CrmStage> stages = new ArrayList<>();
    @CreatedDate
    private Instant creationTime;
    @LastModifiedDate
    private Instant lastUpdateTime;
    private String createdBy;
    private String lastModifiedBy;
    @Version
    private Long version;
}
