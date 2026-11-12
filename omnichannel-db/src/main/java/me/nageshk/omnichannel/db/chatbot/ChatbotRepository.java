package me.nageshk.omnichannel.db.chatbot;

import java.util.List;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface ChatbotRepository extends MongoRepository<Chatbot, String> {
    List<Chatbot> findAllByWorkspaceIdOrderByNameAsc(String workspaceId);

    boolean existsByWorkspaceIdAndNameIgnoreCase(String workspaceId, String name);
}
