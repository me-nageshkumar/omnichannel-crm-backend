package me.nageshk.omnichannel.inbox;

import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

/** Chats of WhatsApp Web numbers. */
public interface WaWebChatRepository extends MongoRepository<WaWebChat, String> {

    Optional<WaWebChat> findByPhoneNumberIdAndRemoteId(String phoneNumberId, String remoteId);

    Optional<WaWebChat> findByIdAndWorkspaceId(String id, String workspaceId);

    /** Only the {@code id} of each chat is loaded. */
    @Query(value = "{ 'phoneNumberId': ?0 }", fields = "{ '_id': 1 }")
    List<WaWebChat> findIdsByPhoneNumberId(String phoneNumberId);

    void deleteByPhoneNumberId(String phoneNumberId);
}
