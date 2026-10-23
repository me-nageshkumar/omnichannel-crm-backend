package me.nageshk.omnichannel.crm.contact;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

/** One brand contact on one channel number: the person as the CRM tracks them. */
@Document("crmContacts")
@CompoundIndex(name = "ws_contact_channel", def = "{'workspaceId': 1, 'contactId': 1, 'channel.phoneNumberId': 1}",
        unique = true)
@CompoundIndex(name = "ws_pipelines", def = "{'workspaceId': 1, 'pipelineIds': 1, '_id': -1}")
@CompoundIndex(name = "ws_company", def = "{'workspaceId': 1, 'companyId': 1}")
@CompoundIndex(name = "ws_owner", def = "{'workspaceId': 1, 'ownerId': 1}")
@CompoundIndex(name = "ws_lifecycle", def = "{'workspaceId': 1, 'lifecycleStatus': 1}")
@Getter
@Setter
public class CrmContact {

    @Id
    private String id;
    private String workspaceId;
    private String customerId;
    private String contactId;
    private Channel channel;
    /** Snapshot of the brand contact's phone. */
    private String phone;
    private ContactSnapshot contactSnapshot;
    private Origin origin;
    private String firstName;
    private String lastName;
    private String email;
    private String jobTitle;
    private String companyId;
    private String ownerId;
    private String teamId;
    private String lifecycleStatus;
    private String source;
    private Map<String, Object> customFields = new LinkedHashMap<>();
    /** Pipelines of all the contact's deals, any status. */
    private List<String> pipelineIds = new ArrayList<>();
    private int openDealCount;
    @Field(targetType = FieldType.DECIMAL128)
    private BigDecimal openDealAmount = BigDecimal.ZERO;
    private Instant lastActivityAt;
    private Instant nextTaskDueAt;
    private List<ChannelChange> channelHistory = new ArrayList<>();
    @CreatedDate
    private Instant creationTime;
    @LastModifiedDate
    private Instant lastUpdateTime;
    private String createdBy;
    private String lastModifiedBy;
    @Version
    private Long version;
}
