package me.nageshk.omnichannel.service.chatbot.node;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import me.nageshk.omnichannel.db.chatbot.TaskKind;
import me.nageshk.omnichannel.service.chatbot.flow.Flow;
import me.nageshk.omnichannel.service.chatbot.flow.FlowNode;
import me.nageshk.omnichannel.service.chatbot.flow.FlowParser;
import me.nageshk.omnichannel.service.chatbot.flow.NodeType;
import me.nageshk.omnichannel.service.chatbot.flow.settings.AskFormSettings;
import me.nageshk.omnichannel.service.chatbot.flow.settings.FormField;
import me.nageshk.omnichannel.service.chatbot.flow.settings.FormFieldType;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import me.nageshk.omnichannel.db.chatbot.ChatbotForm;
import me.nageshk.omnichannel.db.chatbot.Waiting;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import me.nageshk.omnichannel.service.chatbot.flow.Outputs;
import me.nageshk.omnichannel.service.chatbot.form.FormPageService;
import me.nageshk.omnichannel.service.chatbot.run.ChatbotSender;
import me.nageshk.omnichannel.service.chatbot.run.RunChat;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/** Ask with a form (spec §4.2, §8.4): a hosted form page whose link goes out over WhatsApp; waits for the submission. */
@Component
public class AskFormExecutor extends BaseExecutor {

    static final int MAX_FIELDS = 20;

    private final FormPageService forms;
    private final RunChat runChat;

    public AskFormExecutor(FlowParser parser, @Lazy FormPageService forms, RunChat runChat) {
        super(parser);
        this.forms = forms;
        this.runChat = runChat;
    }

    @Override
    public StepResult run(RunContext ctx, FlowNode node) {
        AskFormSettings s = settings(node, AskFormSettings.class);
        WaWebChat chat = runChat.resolve(ctx);
        AskFormSettings rendered = new AskFormSettings(ctx.render(s.title()), ctx.render(s.intro()), renderFields(ctx, s),
                ctx.render(s.submitText()), ctx.render(s.thanksText()), s.linkMessage());
        // the form lives at least a day, and as long as the run may wait (spec §5.6: the token expires with the run)
        Duration life = Duration.ofMinutes(Math.max(ctx.settings().endAfterMinutes(), 24 * 60));
        Instant expiresAt = ctx.now().plus(life);
        ChatbotForm form = forms.create(ctx.run(), node, rendered, expiresAt);
        Map<String, Object> formVars = new LinkedHashMap<>();
        formVars.put("url", forms.url(form));
        Object previous = ctx.vars().put("form", formVars);
        String link = ctx.render(s.linkMessage());
        if (previous == null) ctx.vars().remove("form"); else ctx.vars().put("form", previous);
        ctx.sender(ChatbotSender.class).send(ctx, SendMessageExecutor.textMessage(link));
        return StepResult.wait(Waiting.form(form.getToken(), ctx.now(), expiresAt, rendered.title()),
                "Sent the form “" + rendered.title() + "” to " + chat.getRemoteId());
    }

    private static List<FormField> renderFields(RunContext ctx, AskFormSettings s) {
        List<FormField> out = new ArrayList<>();
        if (s.fields() == null) return out;
        for (FormField f : s.fields()) {
            out.add(new FormField(f.key(), ctx.render(f.label()), f.type(), f.required(), f.options()));
        }
        return out;
    }

    @Override
    public StepResult onAnswer(RunContext ctx, FlowNode node, TaskAnswer answer) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("form", new LinkedHashMap<>(answer.payload()));
        return StepResult.next(Outputs.NEXT, result, "Form submitted");
    }

    @Override
    public NodeType type() {
        return NodeType.ASK_FORM;
    }

    @Override
    public Set<TaskKind> waitsFor(FlowNode node, Flow flow) {
        return Set.of(TaskKind.FORM);
    }

    @Override
    public List<Problem> validate(FlowNode node, Flow flow, ValidationContext ctx) {
        AskFormSettings s = settings(node, AskFormSettings.class);
        Problems p = new Problems(node);
        p.text("title", s.title(), 120, true);
        p.text("intro", s.intro(), 1000, false);
        p.text("submitText", s.submitText(), 200, false);
        p.text("thanksText", s.thanksText(), 200, false);
        if (!p.text("linkMessage", s.linkMessage(), 1024, true)) { /* reported */ }
        else if (!s.linkMessage().contains("{{form.url}}")) p.error("linkMessage", "must contain {{form.url}}");
        if (s.fields() == null || s.fields().isEmpty() || s.fields().size() > MAX_FIELDS) {
            p.error("fields", "needs 1 to 20 fields");
            return p.list;
        }
        Set<String> keys = new HashSet<>();
        for (int i = 0; i < s.fields().size(); i++) {
            FormField f = s.fields().get(i);
            String at = "fields[" + i + "]";
            if (f == null) {
                p.error(at, "is required");
                continue;
            }
            if (blank(f.key()) || !AskQuestionExecutor.SAVE_AS.matcher(f.key()).matches()) {
                p.error(at + ".key", "must be letters, digits or underscores and not start with a digit");
            } else if (!keys.add(f.key())) {
                p.error(at + ".key", "another field has this key");
            }
            p.text(at + ".label", f.label(), 80, true);
            if (f.type() == null) p.error(at + ".type", "is required");
            if (f.type() == FormFieldType.CHOICE && (f.options() == null || f.options().stream().filter(o -> !blank(o)).count() < 2)) {
                p.error(at + ".options", "a choice needs at least 2 options");
            }
        }
        return p.list;
    }
}
