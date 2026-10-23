package me.nageshk.omnichannel.crm.company;

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

/** An organisation contacts and deals belong to; visible brand-wide. */
@Document("crmCompanies")
@CompoundIndex(name = "ws_id", def = "{'workspaceId': 1, '_id': -1}")
@CompoundIndex(name = "ws_name", def = "{'workspaceId': 1, 'name': 1}")
@CompoundIndex(name = "ws_owner", def = "{'workspaceId': 1, 'ownerId': 1}")
@Getter
@Setter
public class CrmCompany {

    @Id
    private String id;
    private String workspaceId;
    private String customerId;
    private String name;
    private String domain;
    private String website;
    private String industry;
    private String size;
    @Field(targetType = FieldType.DECIMAL128)
    private BigDecimal annualRevenue;
    private String phone;
    private String email;
    private Address address;
    private String description;
    private String ownerId;
    private String teamId;
    private List<String> labelIds = new ArrayList<>();
    private Map<String, Object> customFields = new LinkedHashMap<>();
    /** Derived: CRM contacts linked to the company. */
    private int contactCount;
    /** Derived: sum of the amounts of the company's open deals. */
    @Field(targetType = FieldType.DECIMAL128)
    private BigDecimal openDealAmount = BigDecimal.ZERO;
    private Instant lastActivityAt;
    @CreatedDate
    private Instant creationTime;
    @LastModifiedDate
    private Instant lastUpdateTime;
    private String createdBy;
    private String lastModifiedBy;
    @Version
    private Long version;
}
