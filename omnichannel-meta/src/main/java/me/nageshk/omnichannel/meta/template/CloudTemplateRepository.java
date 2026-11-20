package me.nageshk.omnichannel.meta.template;

import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface CloudTemplateRepository extends MongoRepository<CloudTemplate, String> {
    List<CloudTemplate> findByWorkspaceIdAndWabaIdOrderByNameAscLanguageAsc(String workspaceId, String wabaId);
    List<CloudTemplate> findByWorkspaceIdOrderByNameAscLanguageAsc(String workspaceId);
    Optional<CloudTemplate> findByIdAndWorkspaceId(String id, String workspaceId);
    Optional<CloudTemplate> findByWorkspaceIdAndWabaIdAndMetaTemplateId(String workspaceId, String wabaId,
            String metaTemplateId);
    Optional<CloudTemplate> findFirstByConnectionIdAndMetaTemplateId(String connectionId, String metaTemplateId);
}
