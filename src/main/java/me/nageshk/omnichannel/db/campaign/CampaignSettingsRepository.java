package me.nageshk.omnichannel.db.campaign;

import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface CampaignSettingsRepository extends MongoRepository<CampaignSettings, String> {

    Optional<CampaignSettings> findByWorkspaceId(String workspaceId);
}
