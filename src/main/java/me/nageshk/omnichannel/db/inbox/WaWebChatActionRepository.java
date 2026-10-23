package me.nageshk.omnichannel.db.inbox;

import java.util.Collection;
import org.springframework.data.mongodb.repository.MongoRepository;

/** Audit trail of WhatsApp Web chats. */
public interface WaWebChatActionRepository extends MongoRepository<WaWebChatAction, String> {

    void deleteByChatIdIn(Collection<String> chatIds);
}
