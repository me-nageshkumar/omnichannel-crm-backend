package me.nageshk.omnichannel.app.ai;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import me.nageshk.omnichannel.ai.api.AiProfileUsage;
import me.nageshk.omnichannel.db.chatbot.Chatbot;
import me.nageshk.omnichannel.db.chatbot.ChatbotRepository;
import me.nageshk.omnichannel.db.chatbot.ChatbotVersion;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

/** Which chatbots have an {@code ai} node on a profile, in the draft or in any saved version. */
@Component
class AiProfileUsageAdapter implements AiProfileUsage {

    private final MongoTemplate mongo;
    private final ChatbotRepository chatbots;

    AiProfileUsageAdapter(MongoTemplate mongo, ChatbotRepository chatbots) {
        this.mongo = mongo;
        this.chatbots = chatbots;
    }

    @Override
    public List<String> chatbotsUsing(String workspaceId, String profileId) {
        Criteria node = Criteria.where("flow.nodes").elemMatch(
                Criteria.where("type").is("ai").and("data.settings.profileId").is(profileId));
        Query q = new Query(Criteria.where("workspaceId").is(workspaceId).andOperator(node));
        Set<String> names = new TreeSet<>();
        mongo.find(q, Chatbot.class).forEach(c -> names.add(c.getName()));
        Set<String> viaVersions = new TreeSet<>();
        mongo.find(q, ChatbotVersion.class).forEach(v -> viaVersions.add(v.getChatbotId()));
        if (!viaVersions.isEmpty()) {
            chatbots.findAllById(viaVersions).stream().filter(c -> workspaceId.equals(c.getWorkspaceId()))
                    .forEach(c -> names.add(c.getName()));
        }
        return List.copyOf(names);
    }
}
