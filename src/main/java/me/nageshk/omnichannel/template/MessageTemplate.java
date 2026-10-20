package me.nageshk.omnichannel.template;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import me.nageshk.omnichannel.phone.ConnectionType;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

/** A reusable message a brand sends: the name is unique per brand ignoring case, archived templates included. */
@Document("message_templates")
@CompoundIndex(name = "ws_nameKey", def = "{'workspaceId': 1, 'nameKey': 1}", unique = true)
@CompoundIndex(name = "ws_status_nameKey", def = "{'workspaceId': 1, 'status': 1, 'nameKey': 1}")
@Getter
@Setter
public class MessageTemplate {

    @Id
    private String id;
    private String workspaceId;
    private String customerId;
    private ConnectionType channel;
    private String name;
    private String nameKey;
    private String category;
    private TemplateType type;
    private TemplateContent content;
    private TemplateMediaRef media;
    private List<TemplatePlaceholder> placeholders = new ArrayList<>();
    private TemplateStatus status;
    /** Null until the send feature uses the template; a used template can be archived but not deleted. */
    private Instant lastUsedAt;
    private String createdByUsername;
    private String createdByName;
    private String updatedByUsername;
    private String updatedByName;
    @CreatedDate
    private Instant creationTime;
    @LastModifiedDate
    private Instant lastUpdateTime;
    @Version
    private Long version;
}
