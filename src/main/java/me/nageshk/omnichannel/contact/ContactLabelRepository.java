package me.nageshk.omnichannel.contact;

import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

/** Contact labels by brand. */
public interface ContactLabelRepository extends MongoRepository<ContactLabel, String> {

    List<ContactLabel> findByWorkspaceIdOrderByNameKey(String workspaceId);

    Optional<ContactLabel> findByIdAndWorkspaceId(String id, String workspaceId);

    Optional<ContactLabel> findByWorkspaceIdAndNameKey(String workspaceId, String nameKey);
}
