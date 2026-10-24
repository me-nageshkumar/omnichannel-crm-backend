package me.nageshk.omnichannel.crm.deal;

import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface CrmDealRepository extends MongoRepository<CrmDeal, String> {

    Optional<CrmDeal> findByIdAndWorkspaceId(String id, String workspaceId);
}
