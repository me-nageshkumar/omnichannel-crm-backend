package me.nageshk.omnichannel.crm.access;

import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface CrmPipelineAccessRepository extends MongoRepository<CrmPipelineAccess, String> {

    Optional<CrmPipelineAccess> findByWorkspaceIdAndUserId(String workspaceId, String userId);
}
