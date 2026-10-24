package me.nageshk.omnichannel.crm.field;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import me.nageshk.omnichannel.crm.common.TargetType;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

/** A brand's extra field on contacts, companies or deals; values live in each record's {@code customFields}. */
@Document("crmFieldDefinitions")
@CompoundIndex(name = "ws_entity_key", def = "{'workspaceId': 1, 'entityType': 1, 'key': 1}", unique = true)
@CompoundIndex(name = "ws_entity_active_position", def = "{'workspaceId': 1, 'entityType': 1, 'active': 1, 'position': 1}")
@Getter
@Setter
public class CrmFieldDefinition {

    @Id
    private String id;
    private String workspaceId;
    private String customerId;
    /** CONTACT, COMPANY or DEAL. */
    private TargetType entityType;
    /** Immutable once created; the record's {@code customFields} are keyed by it. */
    private String key;
    private String label;
    private FieldType type;
    private List<FieldOption> options = new ArrayList<>();
    private boolean required;
    private Object defaultValue;
    private int position;
    private boolean showInList;
    private boolean showInCard;
    /** Deleting a field only clears this; stored values are kept. */
    private boolean active = true;
    @CreatedDate
    private Instant creationTime;
    @LastModifiedDate
    private Instant lastUpdateTime;
    private String createdBy;
    private String lastModifiedBy;
    @Version
    private Long version;
}
