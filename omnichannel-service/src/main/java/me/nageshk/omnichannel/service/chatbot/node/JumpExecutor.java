package me.nageshk.omnichannel.service.chatbot.node;

import java.util.List;
import me.nageshk.omnichannel.db.chatbot.ChatbotRepository;
import me.nageshk.omnichannel.service.chatbot.flow.Flow;
import me.nageshk.omnichannel.service.chatbot.flow.FlowNode;
import me.nageshk.omnichannel.service.chatbot.flow.FlowParser;
import me.nageshk.omnichannel.service.chatbot.flow.NodeType;
import me.nageshk.omnichannel.service.chatbot.flow.settings.JumpSettings;
import org.springframework.stereotype.Component;

/** Jump to chatbot (spec §4.2): ends this run and starts another chatbot of the brand with the same variables. */
@Component
public class JumpExecutor extends BaseExecutor {

    private final ChatbotRepository chatbots;

    public JumpExecutor(FlowParser parser, ChatbotRepository chatbots) {
        super(parser);
        this.chatbots = chatbots;
    }

    @Override
    public NodeType type() {
        return NodeType.JUMP;
    }

    @Override
    public List<String> outputs(FlowNode node, ValidationContext ctx) {
        return List.of();
    }

    @Override
    public List<Problem> validate(FlowNode node, Flow flow, ValidationContext ctx) {
        JumpSettings s = settings(node, JumpSettings.class);
        Problems p = new Problems(node);
        if (blank(s.chatbotId())) {
            p.error("chatbotId", "choose a chatbot");
        } else if (!s.chatbotId().equals(ctx.chatbotId())
                && chatbots.findById(s.chatbotId()).filter(c -> ctx.workspaceId().equals(c.getWorkspaceId())).isEmpty()) {
            p.error("chatbotId", "the chatbot no longer exists");
        }
        return p.list;
    }
}
