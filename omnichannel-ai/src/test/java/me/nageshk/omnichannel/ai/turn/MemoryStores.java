package me.nageshk.omnichannel.ai.turn;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import me.nageshk.omnichannel.ai.call.AiCall;
import me.nageshk.omnichannel.ai.call.AiCallRepository;
import me.nageshk.omnichannel.ai.conversation.AiConversation;
import me.nageshk.omnichannel.ai.conversation.AiConversationRepository;
import tools.jackson.databind.json.JsonMapper;

/** In-memory stand-ins for the two repositories; stored documents are copies, as in MongoDB. */
final class MemoryStores {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private MemoryStores() {
    }

    static final class Conversations implements AiConversationRepository {
        final Map<String, AiConversation> rows = new LinkedHashMap<>();

        @Override
        public AiConversation save(AiConversation c) {
            if (c.getId() == null) c.setId(UUID.randomUUID().toString());
            rows.put(c.getId(), copy(c));
            return c;
        }

        @Override
        public Optional<AiConversation> findByIdAndWorkspaceId(String id, String workspaceId) {
            return Optional.ofNullable(rows.get(id)).filter(c -> workspaceId.equals(c.getWorkspaceId()))
                    .map(MemoryStores::copy);
        }

        AiConversation get(String id) {
            return rows.get(id);
        }
    }

    static final class Calls implements AiCallRepository {
        final Map<String, AiCall> rows = new LinkedHashMap<>();

        @Override
        public AiCall save(AiCall c) {
            if (c.getId() == null) c.setId(UUID.randomUUID().toString());
            rows.put(c.getId(), c);
            return c;
        }

        @Override
        public Optional<AiCall> findByIdAndWorkspaceId(String id, String workspaceId) {
            return Optional.ofNullable(rows.get(id)).filter(c -> workspaceId.equals(c.getWorkspaceId()));
        }
    }

    private static AiConversation copy(AiConversation c) {
        return JSON.convertValue(c, AiConversation.class);
    }
}
