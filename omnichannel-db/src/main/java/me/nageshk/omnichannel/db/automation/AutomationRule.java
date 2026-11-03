package me.nageshk.omnichannel.db.automation;

import java.time.Instant;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

/** An automation: when an event happens and the conditions hold, run the actions. */
@Document("automationRules")
@CompoundIndex(name = "ws_event_status", def = "{'workspaceId': 1, 'event': 1, 'status': 1}")
@Getter
@Setter
public class AutomationRule {

    @Id
    private String id;
    private String customerId;
    private String workspaceId;
    private String name;
    private String description;
    private AutomationEventKind event;
    private ConditionNode conditions;
    private List<AutomationAction> actions;
    private RuleStatus status;
    private Integer priority;
    private Instant publishedAt;
    private String publishedByUserId;
    private String publishedByName;
    private String createdByUserId;
    private String createdByName;
    @CreatedDate
    private Instant creationTime;
    @LastModifiedDate
    private Instant lastUpdateTime;
    @Version
    private Long version;
}
