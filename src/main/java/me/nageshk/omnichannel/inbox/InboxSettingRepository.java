package me.nageshk.omnichannel.inbox;

import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface InboxSettingRepository extends MongoRepository<InboxSetting, String> {
    /** The brand default when {@code teamId} is null, else that team's override. */
    Optional<InboxSetting> findByWorkspaceIdAndTeamId(String workspaceId, String teamId);

    List<InboxSetting> findByWorkspaceId(String workspaceId);

    void deleteByWorkspaceIdAndTeamId(String workspaceId, String teamId);
}
