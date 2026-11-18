package me.nageshk.omnichannel.ai.profile;

import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface AiProfileRepository extends MongoRepository<AiProfile, String> {

    List<AiProfile> findByWorkspaceIdOrderByName(String workspaceId);

    Optional<AiProfile> findByIdAndWorkspaceId(String id, String workspaceId);

    Optional<AiProfile> findByWorkspaceIdAndName(String workspaceId, String name);

    List<AiProfile> findByWorkspaceIdAndConnectionId(String workspaceId, String connectionId);

    List<AiProfile> findByWorkspaceIdAndVectorStoreIdsContaining(String workspaceId, String vectorStoreId);
}
