package me.nageshk.omnichannel.db.chatbot;

import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface ChatbotFormRepository extends MongoRepository<ChatbotForm, String> {
    Optional<ChatbotForm> findByToken(String token);
}
