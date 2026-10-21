package me.nageshk.omnichannel.campaign;

import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

/** Campaigns by brand. */
public interface CampaignRepository extends MongoRepository<Campaign, String> {

    Optional<Campaign> findByIdAndWorkspaceId(String id, String workspaceId);

    Optional<Campaign> findByWorkspaceIdAndNameKey(String workspaceId, String nameKey);
}
