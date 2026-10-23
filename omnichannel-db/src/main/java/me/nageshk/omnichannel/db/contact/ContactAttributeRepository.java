package me.nageshk.omnichannel.db.contact;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

/** Contact attributes by brand. */
public interface ContactAttributeRepository extends MongoRepository<ContactAttribute, String> {

    List<ContactAttribute> findByWorkspaceIdOrderByNameKey(String workspaceId);

    Optional<ContactAttribute> findByIdAndWorkspaceId(String id, String workspaceId);

    Optional<ContactAttribute> findByWorkspaceIdAndNameKey(String workspaceId, String nameKey);

    Optional<ContactAttribute> findByWorkspaceIdAndKey(String workspaceId, String key);

    List<ContactAttribute> findByWorkspaceIdAndIdIn(String workspaceId, Collection<String> ids);
}
