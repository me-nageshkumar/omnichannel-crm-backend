package me.nageshk.omnichannel.db.automation;

import java.time.Instant;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

/** A live test of a rule: waits for the next matching event, captures it and runs the rule against it. */
@Document("automationTests")
@CompoundIndexes({
        @CompoundIndex(name = "rule_active", def = "{'ruleId': 1}", unique = true, partialFilter = "{'active': true}"),
        @CompoundIndex(name = "ws_event_status", def = "{'workspaceId': 1, 'event': 1, 'status': 1}"),
        @CompoundIndex(name = "active_activity", def = "{'active': 1, 'lastActivityAt': 1}")
})
@Getter
@Setter
public class AutomationTest {

    @Id
    private String id;
    private String customerId;
    private String workspaceId;
    private String ruleId;
    private AutomationEventKind event;
    /** True until the test ends; the unique index allows one active test per rule. */
    private boolean active;
    private TestStatus status;
    private TestEndReason endReason;
    private String startedByUserId;
    private String startedByName;
    private String targetContactId;
    private String targetPhone;
    private String targetName;
    private boolean treatAsNew;
    private String eventId;
    private Instant capturedAt;
    private EventData data;
    private List<ConditionResult> conditionResults;
    private Boolean matched;
    private String runId;
    private Integer nextAction;
    private Instant waitingSince;
    private Instant lastActivityAt;
    @CreatedDate
    private Instant creationTime;
    @Version
    private Long version;
}
