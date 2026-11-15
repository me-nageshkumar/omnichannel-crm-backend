package me.nageshk.omnichannel.ai.call;

import java.util.Optional;
import org.springframework.data.repository.Repository;

/** Reads of single calls; the queue itself works on {@code MongoTemplate} ({@link MongoAiTurnQueue}). */
public interface AiCallRepository extends Repository<AiCall, String> {

    AiCall save(AiCall c);

    Optional<AiCall> findByIdAndWorkspaceId(String id, String workspaceId);
}
