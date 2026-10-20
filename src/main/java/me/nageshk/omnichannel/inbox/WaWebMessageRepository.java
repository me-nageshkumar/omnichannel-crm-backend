package me.nageshk.omnichannel.inbox;

import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

/** Messages of WhatsApp Web chats. */
public interface WaWebMessageRepository extends MongoRepository<WaWebMessage, String> {

    Optional<WaWebMessage> findByPhoneNumberIdAndWaMessageId(String phoneNumberId, String waMessageId);

    Optional<WaWebMessage> findByIdAndChatId(String id, String chatId);

    void deleteByPhoneNumberId(String phoneNumberId);
}
