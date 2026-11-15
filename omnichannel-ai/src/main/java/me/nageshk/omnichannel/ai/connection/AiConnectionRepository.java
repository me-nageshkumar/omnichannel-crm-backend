package me.nageshk.omnichannel.ai.connection;

import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface AiConnectionRepository extends MongoRepository<AiConnection, String> {

    List<AiConnection> findByWorkspaceIdOrderByName(String workspaceId);

    Optional<AiConnection> findByIdAndWorkspaceId(String id, String workspaceId);

    Optional<AiConnection> findByWorkspaceIdAndName(String workspaceId, String name);
}
