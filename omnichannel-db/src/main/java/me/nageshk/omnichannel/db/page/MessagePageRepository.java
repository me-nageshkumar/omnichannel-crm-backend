package me.nageshk.omnichannel.db.page;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface MessagePageRepository extends MongoRepository<MessagePage, String> {

    Optional<MessagePage> findByToken(String token);

    List<MessagePage> findByChatIdAndCreatedAtAfterOrderByCreatedAtDesc(String chatId, Instant after);

    List<MessagePage> findByPreviewReadyTrueAndExpiresAtBefore(Instant now);
}
