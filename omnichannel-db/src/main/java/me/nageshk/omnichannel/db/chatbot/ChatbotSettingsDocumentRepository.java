package me.nageshk.omnichannel.db.chatbot;

import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface ChatbotSettingsDocumentRepository extends MongoRepository<ChatbotSettingsDocument, String> {
    Optional<ChatbotSettingsDocument> findByScopeAndWorkspaceId(ChatbotSettingsScope scope, String workspaceId);
}
