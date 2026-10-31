package me.nageshk.omnichannel.crm.segment;

import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface CrmSegmentRepository extends MongoRepository<CrmSegment, String> {

    List<CrmSegment> findByWorkspaceIdOrderByName(String workspaceId);

    Optional<CrmSegment> findByIdAndWorkspaceId(String id, String workspaceId);

    Optional<CrmSegment> findByWorkspaceIdAndNameKey(String workspaceId, String nameKey);
}
