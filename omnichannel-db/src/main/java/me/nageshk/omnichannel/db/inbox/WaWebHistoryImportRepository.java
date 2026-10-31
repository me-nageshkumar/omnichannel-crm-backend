package me.nageshk.omnichannel.db.inbox;

import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

/** History imports of WhatsApp Web numbers. */
public interface WaWebHistoryImportRepository extends MongoRepository<WaWebHistoryImport, String> {

    Optional<WaWebHistoryImport> findByIdAndWorkspaceId(String id, String workspaceId);

    Optional<WaWebHistoryImport> findByPhoneNumberIdAndScopeKeyAndActiveTrue(String phoneNumberId, String scopeKey);

    List<WaWebHistoryImport> findByActiveTrueOrderByCreationTimeAsc();

    void deleteByPhoneNumberId(String phoneNumberId);
}
