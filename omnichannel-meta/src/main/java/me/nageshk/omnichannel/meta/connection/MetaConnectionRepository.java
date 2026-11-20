package me.nageshk.omnichannel.meta.connection;

import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface MetaConnectionRepository extends MongoRepository<MetaConnection, String> {
    List<MetaConnection> findByWorkspaceIdOrderByName(String workspaceId);
    Optional<MetaConnection> findByIdAndWorkspaceId(String id, String workspaceId);
    Optional<MetaConnection> findByAppId(String appId);
    Optional<MetaConnection> findByWebhookKey(String webhookKey);
}
