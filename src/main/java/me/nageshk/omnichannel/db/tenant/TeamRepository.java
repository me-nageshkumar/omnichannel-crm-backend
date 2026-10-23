package me.nageshk.omnichannel.db.tenant;

import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface TeamRepository extends MongoRepository<Team, String> {

    List<Team> findByWorkspaceIdOrderByName(String workspaceId);

    Optional<Team> findByIdAndWorkspaceId(String id, String workspaceId);

    /** Every team, in any workspace, the user is a member of. */
    List<Team> findByMemberIdsContaining(String userId);
}
