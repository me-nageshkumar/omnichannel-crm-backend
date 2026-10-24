package me.nageshk.omnichannel.crm.access;

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

/** The pipelines an admin assigned to one user of a brand. */
@Document("crmPipelineAccess")
@CompoundIndex(name = "ws_user", def = "{'workspaceId': 1, 'userId': 1}", unique = true)
@CompoundIndex(name = "ws_pipelines", def = "{'workspaceId': 1, 'pipelineIds': 1}")
@Getter
@Setter
public class CrmPipelineAccess {

    @Id
    private String id;
    private String workspaceId;
    private String customerId;
    private String userId;
    private List<String> pipelineIds = new ArrayList<>();
    @CreatedDate
    private Instant creationTime;
    @LastModifiedDate
    private Instant lastUpdateTime;
    private String createdBy;
    private String lastModifiedBy;
    @Version
    private Long version;
}
