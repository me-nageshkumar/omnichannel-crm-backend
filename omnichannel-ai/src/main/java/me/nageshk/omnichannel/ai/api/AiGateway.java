package me.nageshk.omnichannel.ai.api;

import java.util.List;
import java.util.Optional;

/** The only door other modules use into the AI module. */
public interface AiGateway {

    /** Inserts a PENDING aiCalls row and returns its id. */
    String submit(TurnRequest r);

    Optional<TurnResult> result(String workspaceId, String callId);

    /** Names only, for the node picker. */
    List<AiProfileRef> profiles(String workspaceId);

    Optional<AiProfileInfo> profile(String workspaceId, String profileId);
}
