package me.nageshk.omnichannel.service.chatbot.node;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import me.nageshk.omnichannel.db.chatbot.TaskKind;
import java.util.LinkedHashMap;
import java.util.Map;
import me.nageshk.omnichannel.db.chatbot.Waiting;
import me.nageshk.omnichannel.db.contact.ContactAttributeRepository;
import me.nageshk.omnichannel.db.contact.ContactLabelRepository;
import me.nageshk.omnichannel.service.chatbot.run.RunVariables;
import me.nageshk.omnichannel.db.inbox.WaWebMessage;
import me.nageshk.omnichannel.service.chatbot.run.ChatbotSender;
import me.nageshk.omnichannel.service.contact.ContactService;
import me.nageshk.omnichannel.service.chatbot.flow.Flow;
import me.nageshk.omnichannel.service.chatbot.flow.FlowNode;
import me.nageshk.omnichannel.service.chatbot.flow.FlowParser;
import me.nageshk.omnichannel.service.chatbot.flow.NodeType;
import me.nageshk.omnichannel.service.chatbot.flow.Outputs;
import me.nageshk.omnichannel.service.chatbot.flow.settings.AnswerType;
import me.nageshk.omnichannel.service.chatbot.flow.settings.AskQuestionSettings;
import org.springframework.stereotype.Component;

/** Ask a question (spec §4.2): a text prompt, an answer type, retries, and where the answer goes. */
@Component
public class AskQuestionExecutor extends BaseExecutor {

    static final Pattern SAVE_AS = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    static final int MAX_RETRIES = 5;

    private final ContactAttributeRepository attributes;
    private final ContactLabelRepository labels;
    private final ContactService contacts;
    private final RunVariables variables;

    public AskQuestionExecutor(FlowParser parser, ContactAttributeRepository attributes, ContactLabelRepository labels,
            ContactService contacts, RunVariables variables) {
        super(parser);
        this.attributes = attributes;
        this.labels = labels;
        this.contacts = contacts;
        this.variables = variables;
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

    // ---- running ----

    @Override
    public StepResult run(RunContext ctx, FlowNode node) {
        AskQuestionSettings s = settings(node, AskQuestionSettings.class);
        String text = ctx.render(s.text());
        ctx.sender(ChatbotSender.class).send(ctx, SendMessageExecutor.textMessage(text));
        return StepResult.wait(Waiting.whatsApp(ctx.chat().getId(), ctx.now(), text), "Asked “" + text + "”");
    }

    @Override
    public StepResult onAnswer(RunContext ctx, FlowNode node, TaskAnswer answer) {
        AskQuestionSettings s = settings(node, AskQuestionSettings.class);
        java.util.Optional<Object> parsed = parse(s, answer.message());
        if (parsed.isPresent()) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("answer", parsed.get());
            if (s.saveAs() != null && !s.saveAs().isBlank() && !s.saveAs().equals(node.name())) {
                ctx.vars().put(s.saveAs(), new LinkedHashMap<>(result));
            }
            if (s.attributeId() != null && !s.attributeId().isBlank() && ctx.contact() != null) {
                Map<String, String> values = new LinkedHashMap<>();
                values.put(s.attributeId(), String.valueOf(parsed.get()));
                ctx.contact(contacts.update(ctx.workspaceId(), ctx.contact().getId(), null, values));
                variables.refreshContact(ctx.vars(), ctx.contact(), attributes.findByWorkspaceIdOrderByNameKey(ctx.workspaceId()),
                        labels.findByWorkspaceIdOrderByNameKey(ctx.workspaceId()));
            }
            return StepResult.next(Outputs.NEXT, result, "Answered “" + parsed.get() + "”");
        }
        ChatbotSender sender = ctx.sender(ChatbotSender.class);
        int retries = s.retries() == null ? 2 : s.retries();
        if (ctx.run().getInvalidReplies() < retries) {
            ctx.run().setInvalidReplies(ctx.run().getInvalidReplies() + 1);
            String retry = s.retryMessage() == null || s.retryMessage().isBlank() ? ctx.settings().retryMessage() : s.retryMessage();
            sender.send(ctx, SendMessageExecutor.textMessage(ctx.render(retry)));
            return StepResult.wait(Waiting.whatsApp(ctx.chat().getId(), ctx.now(), ctx.render(s.text())),
                    "Answer not valid, asked again (" + ctx.run().getInvalidReplies() + "/" + retries + ")");
        }
        if (s.giveUpMessage() != null && !s.giveUpMessage().isBlank()) {
            sender.send(ctx, SendMessageExecutor.textMessage(ctx.render(s.giveUpMessage())));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("answer", null);
        result.put("gaveUp", true);
        return StepResult.next(Outputs.NO_ANSWER, result, "No valid answer");
    }

    /** The typed answer, or empty when the message is not a valid answer. */
    java.util.Optional<Object> parse(AskQuestionSettings s, WaWebMessage m) {
        return Answers.parse(s.answerType() == null ? AnswerType.TEXT : s.answerType(), m);
    }
}
