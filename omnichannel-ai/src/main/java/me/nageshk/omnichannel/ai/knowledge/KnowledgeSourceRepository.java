package me.nageshk.omnichannel.ai.knowledge;

import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface KnowledgeSourceRepository extends MongoRepository<KnowledgeSource, String> {

    List<KnowledgeSource> findByKnowledgeBaseIdOrderByAddedAt(String knowledgeBaseId);

    List<KnowledgeSource> findByWorkspaceId(String workspaceId);

    Optional<KnowledgeSource> findByIdAndKnowledgeBaseId(String id, String knowledgeBaseId);

    long countByKnowledgeBaseId(String knowledgeBaseId);
}
