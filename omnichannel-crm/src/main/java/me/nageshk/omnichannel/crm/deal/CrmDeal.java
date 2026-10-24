package me.nageshk.omnichannel.crm.deal;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import me.nageshk.omnichannel.crm.contact.Channel;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

/**
 * A sales opportunity with a CRM contact, on that contact's channel, sitting in one lane of a pipeline. Other CRM
 * parts read this collection by name ({@code crmDeals}): keep the field names.
 */
@Document("crmDeals")
@CompoundIndex(name = "ws_lane", def = "{'workspaceId': 1, 'pipelineId': 1, 'stageId': 1, 'position': 1}")
@CompoundIndex(name = "ws_owner_status", def = "{'workspaceId': 1, 'ownerId': 1, 'status': 1}")
@CompoundIndex(name = "ws_primary", def = "{'workspaceId': 1, 'primaryCrmContactId': 1}")
@CompoundIndex(name = "ws_contacts", def = "{'workspaceId': 1, 'crmContactIds': 1}")
@CompoundIndex(name = "ws_company", def = "{'workspaceId': 1, 'companyId': 1}")
@CompoundIndex(name = "ws_status_closed", def = "{'workspaceId': 1, 'status': 1, 'closedAt': 1}")
@CompoundIndex(name = "ws_status_activity", def = "{'workspaceId': 1, 'status': 1, 'lastActivityAt': 1}")
@CompoundIndex(name = "ws_customFields", def = "{'workspaceId': 1, 'customFields.$**': 1}")
@Getter
@Setter
public class CrmDeal {

    @Id
    private String id;
    private String workspaceId;
    private String customerId;
    /** The primary contact's channel. */
    private Channel channel;
    private String title;
    private String pipelineId;
    private String stageId;
    /** Order within the lane, ascending. */
    private double position;
    private DealStatus status = DealStatus.OPEN;
    @Field(targetType = FieldType.DECIMAL128)
    private BigDecimal amount;
    private String currency;
    private Integer probability;
    private boolean probabilityOverridden;
    /** {@code yyyy-MM-dd}. */
    private String expectedCloseDate;
    private Instant closedAt;
    private String lostReason;
    private String companyId;
    private String primaryCrmContactId;
    private ContactSnapshot contactSnapshot;
    /** Every linked contact, the primary first; all on the primary's channel. */
    private List<String> crmContactIds = new ArrayList<>();
    private String ownerId;
    private String teamId;
    private String source;
    private List<String> labelIds = new ArrayList<>();
    private Map<String, Object> customFields = new LinkedHashMap<>();
    private Instant stageEnteredAt;
    private boolean rotting;
    private Instant lastActivityAt;
    private Instant nextTaskDueAt;
    private boolean archived;
    @CreatedDate
    private Instant creationTime;
    @LastModifiedDate
    private Instant lastUpdateTime;
    private String createdBy;
    private String lastModifiedBy;
    @Version
    private Long version;
}
