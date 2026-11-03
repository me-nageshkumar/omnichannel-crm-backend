package me.nageshk.omnichannel.db.automation;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface AutomationTestRepository extends MongoRepository<AutomationTest, String> {

    Optional<AutomationTest> findByIdAndWorkspaceId(String id, String workspaceId);

    Optional<AutomationTest> findByRuleIdAndActiveTrue(String ruleId);

    List<AutomationTest> findByWorkspaceIdAndEventAndStatus(String workspaceId, AutomationEventKind event,
            TestStatus status);

    boolean existsByWorkspaceIdAndEventAndStatus(String workspaceId, AutomationEventKind event, TestStatus status);

    List<AutomationTest> findByActiveTrueAndLastActivityAtBefore(Instant t);
}
