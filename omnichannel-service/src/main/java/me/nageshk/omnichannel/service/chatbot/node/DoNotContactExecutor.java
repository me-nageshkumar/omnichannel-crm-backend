package me.nageshk.omnichannel.service.chatbot.node;

import java.util.List;
import me.nageshk.omnichannel.service.chatbot.flow.Flow;
import me.nageshk.omnichannel.service.chatbot.flow.FlowNode;
import me.nageshk.omnichannel.service.chatbot.flow.FlowParser;
import me.nageshk.omnichannel.service.chatbot.flow.NodeType;
import me.nageshk.omnichannel.service.chatbot.flow.settings.DoNotContactSettings;
import org.springframework.stereotype.Component;

/** Do not contact (spec §4.2): mark or clear the contact's flag. */
@Component
public class DoNotContactExecutor extends BaseExecutor {

    public DoNotContactExecutor(FlowParser parser) {
        super(parser);
    }

    @Override
    public NodeType type() {
        return NodeType.DO_NOT_CONTACT;
    }

    @Override
    public List<Problem> validate(FlowNode node, Flow flow, ValidationContext ctx) {
        settings(node, DoNotContactSettings.class);
        return List.of();
    }
}
