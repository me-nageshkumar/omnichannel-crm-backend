package me.nageshk.omnichannel.crm.contact;

import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface CrmContactRepository extends MongoRepository<CrmContact, String> {

    Optional<CrmContact> findByWorkspaceIdAndContactIdAndChannelPhoneNumberId(String workspaceId, String contactId,
            String phoneNumberId);

    Optional<CrmContact> findByIdAndWorkspaceId(String id, String workspaceId);
}
