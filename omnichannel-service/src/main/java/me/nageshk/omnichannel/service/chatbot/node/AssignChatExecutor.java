package me.nageshk.omnichannel.service.chatbot.node;

import java.util.List;
import me.nageshk.omnichannel.service.automation.AssignTargets;
import me.nageshk.omnichannel.service.chatbot.flow.Flow;
import me.nageshk.omnichannel.service.chatbot.flow.FlowNode;
import me.nageshk.omnichannel.service.chatbot.flow.FlowParser;
import me.nageshk.omnichannel.service.chatbot.flow.NodeType;
import me.nageshk.omnichannel.service.chatbot.flow.settings.AssignChatSettings;
import org.springframework.stereotype.Component;

/** Assign chat (spec §4.2): exactly the automation action's choices. */
@Component
public class AssignChatExecutor extends BaseExecutor {

    private final AssignTargets targets;

    public AssignChatExecutor(FlowParser parser, AssignTargets targets) {
        super(parser);
        this.targets = targets;
    }

    @Override
    public NodeType type() {
        return NodeType.ASSIGN_CHAT;
    }

    @Override
    public List<Problem> validate(FlowNode node, Flow flow, ValidationContext ctx) {
        AssignChatSettings s = settings(node, AssignChatSettings.class);
        Problems p = new Problems(node);
        targets.check(ctx.workspaceId(), s.mode(), s.userId(), s.teamId()).forEach(p::error);
        return p.list;
    }
}
