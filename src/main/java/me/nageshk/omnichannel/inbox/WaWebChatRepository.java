package me.nageshk.omnichannel.inbox;

import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

/** Chats of WhatsApp Web numbers. */
public interface WaWebChatRepository extends MongoRepository<WaWebChat, String> {

    Optional<WaWebChat> findByPhoneNumberIdAndRemoteId(String phoneNumberId, String remoteId);

    Optional<WaWebChat> findByIdAndWorkspaceId(String id, String workspaceId);

    void deleteByPhoneNumberId(String phoneNumberId);
}
