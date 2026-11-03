package me.nageshk.omnichannel.db.automation;

import java.time.Instant;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * One rule's run for one event. A live run is unique per event and rule, so a retried event never runs a rule twice;
 * a test's runs are not, since a replay runs the same event again.
 */
@Document("automationRuns")
@CompoundIndexes({
        @CompoundIndex(name = "event_rule", def = "{'eventId': 1, 'ruleId': 1}", unique = true,
                partialFilter = "{'test': false}"),
        @CompoundIndex(name = "rule_started", def = "{'ruleId': 1, 'startedAt': -1}"),
        @CompoundIndex(name = "ws_started", def = "{'workspaceId': 1, 'startedAt': -1}")
})
@Getter
@Setter
public class AutomationRun {

    @Id
    private String id;
    private String customerId;
    private String workspaceId;
    private String eventId;
    private AutomationEventKind eventKind;
    private String ruleId;
    private String ruleName;
    private boolean test;
    private String testId;
    private RunStatus status;
    private String contactId;
    private String contactName;
    private String contactPhone;
    private String dealId;
    private String dealTitle;
    private String summary;
    private List<RunStep> steps;
    private Instant startedAt;
    private Instant finishedAt;
}
