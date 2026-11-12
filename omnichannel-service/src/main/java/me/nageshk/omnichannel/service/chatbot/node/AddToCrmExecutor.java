package me.nageshk.omnichannel.service.chatbot.node;

import java.util.List;
import me.nageshk.omnichannel.service.automation.AutomationCrm;
import me.nageshk.omnichannel.service.automation.CrmTargets;
import me.nageshk.omnichannel.service.chatbot.flow.Flow;
import me.nageshk.omnichannel.service.chatbot.flow.FlowNode;
import me.nageshk.omnichannel.service.chatbot.flow.FlowParser;
import me.nageshk.omnichannel.service.chatbot.flow.NodeType;
import me.nageshk.omnichannel.service.chatbot.flow.settings.AddToCrmSettings;
import org.springframework.stereotype.Component;

/** Add to CRM (spec §4.2): exactly the automation action's settings. */
@Component
public class AddToCrmExecutor extends BaseExecutor {

    private final AutomationCrm crm;

    public AddToCrmExecutor(FlowParser parser, AutomationCrm crm) {
        super(parser);
        this.crm = crm;
    }

    @Override
    public NodeType type() {
        return NodeType.ADD_TO_CRM;
    }

    @Override
    public List<Problem> validate(FlowNode node, Flow flow, ValidationContext ctx) {
        AddToCrmSettings s = settings(node, AddToCrmSettings.class);
        Problems p = new Problems(node);
        CrmTargets.check(crm.options(ctx.workspaceId()), s.pipelineId(), s.stageId()).forEach(p::error);
        p.text("dealTitle", s.dealTitle(), 200, false);
        return p.list;
    }
}
