package me.nageshk.omnichannel.service.chatbot.node;

import java.util.List;
import me.nageshk.omnichannel.service.automation.TemplateTargets;
import me.nageshk.omnichannel.service.chatbot.flow.Flow;
import me.nageshk.omnichannel.service.chatbot.flow.FlowNode;
import me.nageshk.omnichannel.service.chatbot.flow.FlowParser;
import me.nageshk.omnichannel.service.chatbot.flow.NodeType;
import me.nageshk.omnichannel.service.chatbot.flow.settings.SendTemplateSettings;
import org.springframework.stereotype.Component;

/** Send template (spec §4.2): one of the brand's saved templates with fixed values for its agent placeholders. */
@Component
public class SendTemplateExecutor extends BaseExecutor {

    private final TemplateTargets templates;

    public SendTemplateExecutor(FlowParser parser, TemplateTargets templates) {
        super(parser);
        this.templates = templates;
    }

    @Override
    public NodeType type() {
        return NodeType.SEND_TEMPLATE;
    }

    @Override
    public List<Problem> validate(FlowNode node, Flow flow, ValidationContext ctx) {
        SendTemplateSettings s = settings(node, SendTemplateSettings.class);
        Problems p = new Problems(node);
        templates.check(ctx.workspaceId(), s.templateId(), s.values()).forEach(p::error);
        return p.list;
    }
}
