package me.nageshk.omnichannel.db.automation;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface AutomationRunRepository extends MongoRepository<AutomationRun, String> {

    /** The live run of the rule for the event; a test's runs are left out. */
    Optional<AutomationRun> findByEventIdAndRuleIdAndTestFalse(String eventId, String ruleId);

    List<AutomationRun> findByEventIdIn(Collection<String> eventIds);

    /** The non-test runs of the brand that started at or after {@code from}. */
    List<AutomationRun> findByWorkspaceIdAndTestFalseAndStartedAtGreaterThanEqual(String workspaceId, Instant from);

    Optional<AutomationRun> findFirstByRuleIdAndTestFalseOrderByStartedAtDesc(String ruleId);
}
