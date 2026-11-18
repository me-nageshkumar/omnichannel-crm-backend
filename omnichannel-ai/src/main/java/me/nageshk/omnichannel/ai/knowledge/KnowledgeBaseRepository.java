package me.nageshk.omnichannel.ai.knowledge;

import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface KnowledgeBaseRepository extends MongoRepository<KnowledgeBase, String> {

    List<KnowledgeBase> findByWorkspaceIdOrderByName(String workspaceId);

    Optional<KnowledgeBase> findByIdAndWorkspaceId(String id, String workspaceId);

    Optional<KnowledgeBase> findByWorkspaceIdAndName(String workspaceId, String name);

    List<KnowledgeBase> findByWorkspaceIdAndConnectionId(String workspaceId, String connectionId);

    long countByWorkspaceId(String workspaceId);
}
