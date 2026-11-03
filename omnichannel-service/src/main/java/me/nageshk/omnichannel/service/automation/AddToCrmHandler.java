package me.nageshk.omnichannel.service.automation;

import me.nageshk.omnichannel.db.automation.AutomationAction;
import me.nageshk.omnichannel.db.automation.AutomationActionType;
import me.nageshk.omnichannel.db.automation.AutomationEventKind;
import me.nageshk.omnichannel.db.automation.EventData;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import me.nageshk.omnichannel.db.inbox.WaWebChatRepository;
import org.springframework.stereotype.Component;

/**
 * Adds the person who wrote in to the CRM with a deal, like the inbox's "Add to CRM" button; the deal's owner is
 * whoever the chat is assigned to when the action runs. Someone already in the CRM on the number is left alone.
 */
@Component
public class AddToCrmHandler implements ActionHandler {

    private final AutomationCrm crm;
    private final WaWebChatRepository chats;

    public AddToCrmHandler(AutomationCrm crm, WaWebChatRepository chats) {
        this.crm = crm;
        this.chats = chats;
    }

    @Override
    public AutomationActionType type() {
        return AutomationActionType.ADD_TO_CRM;
    }

    @Override
    public ActionResult run(ActionContext ctx, AutomationAction action) {
        if (ctx.kind() != AutomationEventKind.MESSAGE_RECEIVED) {
            return ActionResult.failed("Add to CRM works on incoming messages only");
        }
        EventData d = ctx.data();
        EventData.ContactData contact = d == null ? null : d.contact();
        String numberId = d == null || d.number() == null ? null : d.number().id();
        String chatId = d == null || d.chat() == null ? null : d.chat().id();
        if (contact == null || contact.id() == null || numberId == null) {
            return ActionResult.failed("No contact to add");
        }
        if (crm.inCrm(ctx.workspaceId(), contact.id(), numberId)) return ActionResult.skipped("Already in the CRM");
        WaWebChat chat = chatId == null ? null : chats.findById(chatId).orElse(null);
        String title = DealTitles.fill(action.dealTitle(), contact.name(), contact.phone());
        try {
            AutomationCrm.LeadResult r = crm.addLead(new AutomationCrm.LeadRequest(ctx.workspaceId(),
                    ctx.customerId(), contact.id(), numberId, chatId, chat == null ? null : chat.getLastInboundAt(),
                    action.pipelineId(), action.stageId(), title, chat == null ? null : chat.getAssignedUserId()));
            return ActionResult.done("Added to " + r.pipelineName() + " › " + r.laneName());
        } catch (AutomationActionFailed e) {
            return ActionResult.failed(e.getMessage());
        }
    }
}
