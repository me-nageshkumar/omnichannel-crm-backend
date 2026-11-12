package me.nageshk.omnichannel.service.chatbot.node;

import java.util.List;
import me.nageshk.omnichannel.service.chatbot.flow.Flow;
import me.nageshk.omnichannel.service.chatbot.flow.FlowNode;
import me.nageshk.omnichannel.service.chatbot.flow.FlowParser;
import me.nageshk.omnichannel.service.chatbot.flow.NodeType;
import me.nageshk.omnichannel.db.contact.DoNotContact;
import me.nageshk.omnichannel.service.chatbot.flow.Outputs;
import me.nageshk.omnichannel.service.chatbot.flow.settings.DoNotContactSettings;
import me.nageshk.omnichannel.service.chatbot.run.NodeFailed;
import me.nageshk.omnichannel.service.contact.ContactService;
import org.springframework.stereotype.Component;

/** Do not contact (spec §4.2): mark or clear the contact's flag. */
@Component
public class DoNotContactExecutor extends BaseExecutor {

    private final ContactService contacts;

    public DoNotContactExecutor(FlowParser parser, ContactService contacts) {
        super(parser);
        this.contacts = contacts;
    }

    @Override
    public StepResult run(RunContext ctx, FlowNode node) {
        DoNotContactSettings s = settings(node, DoNotContactSettings.class);
        if (ctx.contact() == null) throw new NodeFailed("the contact no longer exists");
        String ws = ctx.workspaceId();
        if (s.mark()) {
            ctx.contact(contacts.markDoNotContact(ws, ctx.contact().getId(), DoNotContact.Reason.CHATBOT, null));
            return StepResult.next(Outputs.NEXT, null, "Marked do-not-contact");
        }
        ctx.contact(contacts.clearDoNotContact(ws, ctx.contact().getId()));
        return StepResult.next(Outputs.NEXT, null, "Cleared do-not-contact");
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
