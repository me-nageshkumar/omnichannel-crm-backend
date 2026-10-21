package me.nageshk.omnichannel.campaign;

import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

interface CampaignSettingsRepository extends MongoRepository<CampaignSettings, String> {

    Optional<CampaignSettings> findByWorkspaceId(String workspaceId);
}
