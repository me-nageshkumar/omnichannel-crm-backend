package me.nageshk.omnichannel.db.chatbot;

import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface ChatbotVersionRepository extends MongoRepository<ChatbotVersion, String> {
    List<ChatbotVersion> findAllByChatbotIdOrderByVersionNumberDesc(String chatbotId);

    Optional<ChatbotVersion> findByChatbotIdAndVersionNumber(String chatbotId, int versionNumber);
}
