package me.nageshk.omnichannel.db.automation;

import java.time.Instant;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

/** A finished event; its id is the id of the queued event. */
@Document("automationEventHistory")
@CompoundIndexes({
        @CompoundIndex(name = "ws_created", def = "{'workspaceId': 1, 'createdAt': -1}"),
        @CompoundIndex(name = "ws_kind_created", def = "{'workspaceId': 1, 'kind': 1, 'createdAt': -1}"),
        @CompoundIndex(name = "ws_contact_created", def = "{'workspaceId': 1, 'contactId': 1, 'createdAt': -1}"),
        @CompoundIndex(name = "rule_created", def = "{'ruleIds': 1, 'createdAt': -1}")
})
@Getter
@Setter
public class AutomationEventRecord {

    @Id
    private String id;
    private String customerId;
    private String workspaceId;
    private AutomationEventKind kind;
    private EventData data;
    private String contactId;
    private String dealId;
    private EventOutcome outcome;
    private String error;
    private int attempts;
    private List<String> ruleIds;
    private List<String> testIds;
    private Instant createdAt;
    private Instant finishedAt;
}
