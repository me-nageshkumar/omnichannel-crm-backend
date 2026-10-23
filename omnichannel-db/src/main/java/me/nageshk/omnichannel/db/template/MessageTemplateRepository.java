package me.nageshk.omnichannel.db.template;

import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

/** Message templates by brand. */
public interface MessageTemplateRepository extends MongoRepository<MessageTemplate, String> {

    Optional<MessageTemplate> findByIdAndWorkspaceId(String id, String workspaceId);

    boolean existsByWorkspaceId(String workspaceId);

    Optional<MessageTemplate> findByWorkspaceIdAndNameKey(String workspaceId, String nameKey);
}
