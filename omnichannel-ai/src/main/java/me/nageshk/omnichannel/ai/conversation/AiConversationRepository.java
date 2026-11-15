package me.nageshk.omnichannel.ai.conversation;

import java.util.Optional;
import org.springframework.data.repository.Repository;

/** Only what the turn engine needs, so tests can stand in a small in-memory one. */
public interface AiConversationRepository extends Repository<AiConversation, String> {

    AiConversation save(AiConversation c);

    Optional<AiConversation> findByIdAndWorkspaceId(String id, String workspaceId);
}
