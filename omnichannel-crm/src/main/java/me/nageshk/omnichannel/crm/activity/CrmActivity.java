package me.nageshk.omnichannel.crm.activity;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import me.nageshk.omnichannel.crm.common.CrmTarget;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

/** One append-only entry of a record's timeline. */
@Document("crmActivities")
@CompoundIndex(name = "ws_target", def = "{'workspaceId': 1, 'target.type': 1, 'target.id': 1, 'happenedAt': -1}")
@CompoundIndex(name = "ws_related", def = "{'workspaceId': 1, 'relatedTargets.id': 1, 'happenedAt': -1}")
@Getter
@Setter
public class CrmActivity {

    @Id
    private String id;
    private String workspaceId;
    private String customerId;
    private ActivityType type;
    private CrmTarget target;
    private List<CrmTarget> relatedTargets = new ArrayList<>();
    /** Of the deal the entry concerns; lets reads filter by pipeline access. */
    private String pipelineId;
    private String phoneNumberId;
    private String actorId;
    private ActorType actorType = ActorType.USER;
    private Instant happenedAt;
    private String summary;
    private Map<String, Object> payload = new LinkedHashMap<>();
    @CreatedDate
    private Instant creationTime;
    @LastModifiedDate
    private Instant lastUpdateTime;
    private String createdBy;
    private String lastModifiedBy;
    @Version
    private Long version;
}
