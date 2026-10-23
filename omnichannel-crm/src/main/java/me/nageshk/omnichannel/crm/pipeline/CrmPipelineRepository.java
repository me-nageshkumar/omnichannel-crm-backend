package me.nageshk.omnichannel.crm.pipeline;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface CrmPipelineRepository extends MongoRepository<CrmPipeline, String> {

    long countByWorkspaceId(String workspaceId);
}
