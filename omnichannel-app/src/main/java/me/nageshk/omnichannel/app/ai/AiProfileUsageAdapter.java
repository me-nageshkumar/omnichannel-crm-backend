package me.nageshk.omnichannel.app.ai;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;
import me.nageshk.omnichannel.ai.api.AiProfileUsage;
import me.nageshk.omnichannel.db.chatbot.Chatbot;
import me.nageshk.omnichannel.db.chatbot.ChatbotRepository;
import me.nageshk.omnichannel.db.chatbot.ChatbotRun;
import me.nageshk.omnichannel.db.chatbot.ChatbotRunStatus;
import me.nageshk.omnichannel.db.chatbot.ChatbotVersion;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

/**
 * Which chatbots have an {@code ai} node on a profile in a flow that can still run: the draft, the chatbot's current
 * version (the published one; a published chatbot cannot be edited, so it is the newest) and any older version an
 * active run (RUNNING or WAITING) still points to. Older saved versions nothing runs on do not count.
 */
@Component
class AiProfileUsageAdapter implements AiProfileUsage {

    private final MongoTemplate mongo;
    private final ChatbotRepository chatbots;

    AiProfileUsageAdapter(MongoTemplate mongo, ChatbotRepository chatbots) {
        this.mongo = mongo;
        this.chatbots = chatbots;
    }

    private record Key(String chatbotId, int versionNumber) {}

    @Override
    public List<String> chatbotsUsing(String workspaceId, String profileId) {
        Criteria node = Criteria.where("flow.nodes").elemMatch(
                Criteria.where("type").is("ai").and("data.settings.profileId").is(profileId));
        Query q = new Query(Criteria.where("workspaceId").is(workspaceId).andOperator(node));
        Set<String> names = new TreeSet<>();
        mongo.find(q, Chatbot.class).forEach(c -> names.add(c.getName()));

        Query versionsQuery = new Query(Criteria.where("workspaceId").is(workspaceId).andOperator(node));
        versionsQuery.fields().include("chatbotId", "versionNumber");
        List<ChatbotVersion> versions = mongo.find(versionsQuery, ChatbotVersion.class);
        if (versions.isEmpty()) return List.copyOf(names);
        Set<String> ids = versions.stream().map(ChatbotVersion::getChatbotId).collect(Collectors.toSet());
        Map<String, Chatbot> bots = chatbots.findAllById(ids).stream()
                .filter(c -> workspaceId.equals(c.getWorkspaceId()))
                .collect(Collectors.toMap(Chatbot::getId, Function.identity()));

        Query runsQuery = new Query(Criteria.where("workspaceId").is(workspaceId).and("chatbotId").in(bots.keySet())
                .and("status").in(ChatbotRunStatus.RUNNING, ChatbotRunStatus.WAITING));
        runsQuery.fields().include("chatbotId", "versionNumber");
        Set<Key> running = new HashSet<>();
        mongo.find(runsQuery, ChatbotRun.class).forEach(r -> running.add(new Key(r.getChatbotId(), r.getVersionNumber())));

        for (ChatbotVersion v : versions) {
            Chatbot c = bots.get(v.getChatbotId());
            if (c == null) continue;
            if (v.getVersionNumber() == c.getVersionNumber() || running.contains(new Key(c.getId(), v.getVersionNumber()))) {
                names.add(c.getName());
            }
        }
        return List.copyOf(names);
    }
}
