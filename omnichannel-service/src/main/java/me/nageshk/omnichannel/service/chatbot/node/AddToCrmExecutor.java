package me.nageshk.omnichannel.service.chatbot.node;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import me.nageshk.omnichannel.db.contact.Contact;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import me.nageshk.omnichannel.service.automation.AutomationActionFailed;
import me.nageshk.omnichannel.service.automation.AutomationCrm;
import me.nageshk.omnichannel.service.automation.CrmTargets;
import me.nageshk.omnichannel.service.automation.DealTitles;
import me.nageshk.omnichannel.service.chatbot.flow.Outputs;
import me.nageshk.omnichannel.service.chatbot.run.NodeFailed;
import me.nageshk.omnichannel.service.chatbot.run.RunChat;
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
    private final RunChat runChat;

    public AddToCrmExecutor(FlowParser parser, AutomationCrm crm, RunChat runChat) {
        super(parser);
        this.crm = crm;
        this.runChat = runChat;
    }

    @Override
    public StepResult run(RunContext ctx, FlowNode node) {
        AddToCrmSettings s = settings(node, AddToCrmSettings.class);
        Contact contact = ctx.contact();
        if (contact == null) throw new NodeFailed("the contact no longer exists");
        WaWebChat chat = runChat.resolve(ctx);
        String numberId = chat.getPhoneNumberId();
        Map<String, Object> result = new LinkedHashMap<>();
        if (crm.inCrm(ctx.workspaceId(), contact.getId(), numberId)) {
            result.put("dealId", null);
            result.put("created", false);
            return StepResult.next(Outputs.NEXT, result, "Already in the CRM");
        }
        // {{name}} is the CRM helper's own placeholder; the run's variables fill whatever else the title names
        String title = ctx.render(DealTitles.fill(s.dealTitle(), contact.getName(), contact.getPhone()));
        try {
            AutomationCrm.LeadResult r = crm.addLead(new AutomationCrm.LeadRequest(ctx.workspaceId(), ctx.run().getCustomerId(),
                    contact.getId(), numberId, chat.getId(), chat.getLastInboundAt(), s.pipelineId(), s.stageId(), title,
                    chat.getAssignedUserId()));
            result.put("dealId", r.dealId());
            result.put("created", true);
            return StepResult.next(Outputs.NEXT, result, "Added to " + r.pipelineName() + " › " + r.laneName());
        } catch (AutomationActionFailed e) {
            throw new NodeFailed(e.getMessage());
        }
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
