package me.nageshk.omnichannel.crm.segment;

import java.time.Instant;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

/** A saved, brand-wide audience rule (spec D C3); every preview and send resolves it for the person using it. */
@Document("crmSegments")
@CompoundIndex(name = "ws_nameKey", def = "{'workspaceId': 1, 'nameKey': 1}", unique = true)
@CompoundIndex(name = "ws_name", def = "{'workspaceId': 1, 'name': 1}")
@Getter
@Setter
public class CrmSegment {

    @Id
    private String id;
    private String workspaceId;
    private String customerId;
    private String name;
    /** {@code name} trimmed and lower-cased, for the per-brand uniqueness. */
    private String nameKey;
    private String description;
    private SegmentRuleGroup rules;
    private CachedCounts cachedCounts;
    private Instant lastSentAt;
    private String lastCampaignId;
    private String lastCampaignName;
    @CreatedDate
    private Instant creationTime;
    @LastModifiedDate
    private Instant lastUpdateTime;
    private String createdBy;
    private String lastModifiedBy;
    @Version
    private Long version;
}
