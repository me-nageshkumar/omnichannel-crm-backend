package me.nageshk.omnichannel.db.contact;

import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

/** Saved contact filters by brand. */
public interface ContactFilterRepository extends MongoRepository<ContactFilter, String> {

    List<ContactFilter> findByWorkspaceIdOrderByNameKey(String workspaceId);

    Optional<ContactFilter> findByIdAndWorkspaceId(String id, String workspaceId);

    Optional<ContactFilter> findByWorkspaceIdAndNameKey(String workspaceId, String nameKey);

    List<ContactFilter> findByWorkspaceIdAndKindAndUsesLabelIdsOrderByNameKey(String workspaceId, FilterKind kind,
            String labelId);

    List<ContactFilter> findByWorkspaceIdAndKindAndUsesAttributeIdsOrderByNameKey(String workspaceId, FilterKind kind,
            String attributeId);
}
