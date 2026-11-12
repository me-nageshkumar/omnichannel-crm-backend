package me.nageshk.omnichannel.db.chatbot;

import java.util.Collection;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface ChatbotRunRepository extends MongoRepository<ChatbotRun, String> {
    Optional<ChatbotRun> findFirstByChatIdAndStatusIn(String chatId, Collection<ChatbotRunStatus> statuses);

    Optional<ChatbotRun> findFirstByWorkspaceIdAndContactIdAndStatusIn(String workspaceId, String contactId,
            Collection<ChatbotRunStatus> statuses);

    Optional<ChatbotRun> findFirstByWaitingKindAndWaitingKeyAndStatus(TaskKind kind, String key, ChatbotRunStatus status);

    Optional<ChatbotRun> findFirstByStartedByEventIdAndStartedByRuleIdAndTestFalse(String eventId, String ruleId);

    Page<ChatbotRun> findAllByWorkspaceIdAndChatbotIdOrderByCreatedAtDesc(String workspaceId, String chatbotId,
            Pageable pageable);
}
