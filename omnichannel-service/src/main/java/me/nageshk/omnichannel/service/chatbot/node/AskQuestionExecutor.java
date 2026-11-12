package me.nageshk.omnichannel.service.chatbot.node;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import me.nageshk.omnichannel.db.chatbot.TaskKind;
import me.nageshk.omnichannel.db.contact.ContactAttributeRepository;
import me.nageshk.omnichannel.service.chatbot.flow.Flow;
import me.nageshk.omnichannel.service.chatbot.flow.FlowNode;
import me.nageshk.omnichannel.service.chatbot.flow.FlowParser;
import me.nageshk.omnichannel.service.chatbot.flow.NodeType;
import me.nageshk.omnichannel.service.chatbot.flow.Outputs;
import me.nageshk.omnichannel.service.chatbot.flow.settings.AskQuestionSettings;
import org.springframework.stereotype.Component;

/** Ask a question (spec §4.2): a text prompt, an answer type, retries, and where the answer goes. */
@Component
public class AskQuestionExecutor extends BaseExecutor {

    static final Pattern SAVE_AS = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    static final int MAX_RETRIES = 5;

    private final ContactAttributeRepository attributes;

    public AskQuestionExecutor(FlowParser parser, ContactAttributeRepository attributes) {
        super(parser);
        this.attributes = attributes;
    }

    @Override
    public NodeType type() {
        return NodeType.ASK_QUESTION;
    }

    @Override
    public List<String> outputs(FlowNode node, ValidationContext ctx) {
        return List.of(Outputs.NEXT, Outputs.NO_ANSWER);
    }

    @Override
    public Set<TaskKind> waitsFor(FlowNode node, Flow flow) {
        return Set.of(TaskKind.WHATSAPP_REPLY);
    }

    @Override
    public List<Problem> validate(FlowNode node, Flow flow, ValidationContext ctx) {
        AskQuestionSettings s = settings(node, AskQuestionSettings.class);
        Problems p = new Problems(node);
        p.text("text", s.text(), 1024, true);
        if (s.answerType() == null) p.error("answerType", "is required");
        if (!blank(s.saveAs()) && !SAVE_AS.matcher(s.saveAs()).matches()) {
            p.error("saveAs", "must be letters, digits or underscores and not start with a digit");
        }
        if (s.retries() != null && (s.retries() < 0 || s.retries() > MAX_RETRIES)) p.error("retries", "must be 0 to 5");
        p.text("retryMessage", s.retryMessage(), 1024, false);
        p.text("giveUpMessage", s.giveUpMessage(), 1024, false);
        if (!blank(s.attributeId()) && attributes.findByIdAndWorkspaceId(s.attributeId(), ctx.workspaceId()).isEmpty()) {
            p.error("attributeId", "the attribute no longer exists");
        }
        return p.list;
    }
}
