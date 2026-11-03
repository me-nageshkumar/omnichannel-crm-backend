package me.nageshk.omnichannel.db.automation;

import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface AutomationRuleRepository extends MongoRepository<AutomationRule, String> {

    Optional<AutomationRule> findByIdAndWorkspaceId(String id, String workspaceId);

    List<AutomationRule> findByWorkspaceId(String workspaceId);

    List<AutomationRule> findByWorkspaceIdAndEventAndStatus(String workspaceId, AutomationEventKind event,
            RuleStatus status);

    boolean existsByWorkspaceIdAndEventAndStatus(String workspaceId, AutomationEventKind event, RuleStatus status);
}
