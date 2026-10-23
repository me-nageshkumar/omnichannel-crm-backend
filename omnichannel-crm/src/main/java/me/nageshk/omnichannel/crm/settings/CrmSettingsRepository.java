package me.nageshk.omnichannel.crm.settings;

import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface CrmSettingsRepository extends MongoRepository<CrmSettings, String> {

    Optional<CrmSettings> findByWorkspaceId(String workspaceId);
}
