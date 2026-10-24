package me.nageshk.omnichannel.crm.settings;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import me.nageshk.omnichannel.crm.common.PicklistItem;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

/** The CRM settings of one brand: the platform switch and limits, defaults and picklists. */
@Document("crmSettings")
@CompoundIndex(name = "workspace_unique", def = "{'workspaceId': 1}", unique = true)
@Getter
@Setter
public class CrmSettings {

    @Id
    private String id;
    private String workspaceId;
    private String customerId;
    private boolean enabled;
    /** Null = unlimited. */
    private Integer maxPipelines;
    /** Null = unlimited; per entity type. */
    private Integer maxCustomFields;
    private String defaultCurrency;
    private String defaultPipelineId;
    /** Null = deals never rot. */
    private Integer dealRottingDays;
    private boolean allowChannelMove;
    private boolean timelineShowsWhatsappMessages;
    private List<PicklistItem> lifecycleStatuses = new ArrayList<>();
    private List<PicklistItem> dealSources = new ArrayList<>();
    private List<PicklistItem> taskTypes = new ArrayList<>();
    private List<PicklistItem> lostReasons = new ArrayList<>();
    private List<PicklistItem> industries = new ArrayList<>();
    @CreatedDate
    private Instant creationTime;
    @LastModifiedDate
    private Instant lastUpdateTime;
    private String createdBy;
    private String lastModifiedBy;
    @Version
    private Long version;
}
