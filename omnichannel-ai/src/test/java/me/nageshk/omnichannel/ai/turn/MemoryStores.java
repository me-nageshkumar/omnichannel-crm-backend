package me.nageshk.omnichannel.ai.turn;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import me.nageshk.omnichannel.ai.call.AiCall;
import me.nageshk.omnichannel.ai.call.AiCallRepository;
import me.nageshk.omnichannel.ai.call.AiTurnQueue;
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

    /** Only the staging the runner does; {@code held = false} plays a claim that was lost. */
    static final class Claims implements AiTurnQueue {
        boolean held = true;
        final List<String> staged = new ArrayList<>();
        private final Map<String, AiCall> copies = new LinkedHashMap<>();

        @Override
        public boolean stage(AiCall c) {
            if (!held) return false;
            staged.add(c.getId());
            copies.put(c.getId(), c);
            return true;
        }

        /** The row as the next claim reads it back (the real staging is covered by AiTurnQueueIT). */
        AiCall stagedCopy(String id) {
            return copies.get(id);
        }

        @Override
        public String enqueue(AiCall c) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<AiCall> claim(Instant now) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean finish(AiCall c) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<AiCall> claimRedelivery(Instant now) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void delivered(String id) {
            throw new UnsupportedOperationException();
        }
    }

    private static AiConversation copy(AiConversation c) {
        return JSON.convertValue(c, AiConversation.class);
    }
}
