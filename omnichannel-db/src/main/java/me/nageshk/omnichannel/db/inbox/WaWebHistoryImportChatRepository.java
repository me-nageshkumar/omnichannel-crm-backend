package me.nageshk.omnichannel.db.inbox;

import java.util.Collection;
import org.springframework.data.mongodb.repository.MongoRepository;

/** The chats of history imports. */
public interface WaWebHistoryImportChatRepository extends MongoRepository<WaWebHistoryImportChat, String> {

    boolean existsByImportIdAndStatusIn(String importId, Collection<HistoryChatStatus> statuses);

    long countByImportId(String importId);

    void deleteByImportId(String importId);

    void deleteByPhoneNumberId(String phoneNumberId);
}
