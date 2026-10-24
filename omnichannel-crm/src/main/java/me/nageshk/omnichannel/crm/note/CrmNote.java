package me.nageshk.omnichannel.crm.note;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import me.nageshk.omnichannel.crm.common.CrmTarget;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

/** A markdown note on a contact, company or deal (spec §6.6); visibility follows the target. */
@Document("crmNotes")
@CompoundIndex(name = "ws_target_pinned", def = "{'workspaceId': 1, 'target.type': 1, 'target.id': 1, 'pinned': -1, '_id': -1}")
@Getter
@Setter
public class CrmNote {

    @Id
    private String id;
    private String workspaceId;
    private String customerId;
    private String body;
    private CrmTarget target;
    private String pipelineId;
    private String phoneNumberId;
    private List<NoteAttachment> attachments = new ArrayList<>();
    private boolean pinned;
    @CreatedDate
    private Instant creationTime;
    @LastModifiedDate
    private Instant lastUpdateTime;
    private String createdBy;
    private String lastModifiedBy;
    @Version
    private Long version;
}
