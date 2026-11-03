package me.nageshk.omnichannel.db.inbox;

import java.util.Collection;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

/** Messages of WhatsApp Web chats. */
public interface WaWebMessageRepository extends MongoRepository<WaWebMessage, String> {

    Optional<WaWebMessage> findByPhoneNumberIdAndWaMessageId(String phoneNumberId, String waMessageId);

    Optional<WaWebMessage> findByIdAndChatId(String id, String chatId);

    Optional<WaWebMessage> findFirstByChatIdOrderByTimestampDescIdDesc(String chatId);

    Optional<WaWebMessage> findFirstByChatIdAndDirectionOrderByTimestampDesc(String chatId,
            MessageDirection direction);

    boolean existsByChatIdAndDirectionAndTypeInAndIdNot(String chatId, MessageDirection direction,
            Collection<String> types, String id);

    void deleteByPhoneNumberId(String phoneNumberId);
}
