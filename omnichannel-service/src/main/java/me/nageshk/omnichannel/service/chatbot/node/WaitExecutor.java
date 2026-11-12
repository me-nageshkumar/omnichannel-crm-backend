package me.nageshk.omnichannel.service.chatbot.node;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import me.nageshk.omnichannel.db.chatbot.TaskKind;
import me.nageshk.omnichannel.db.chatbot.Waiting;
import me.nageshk.omnichannel.service.chatbot.flow.Outputs;
import me.nageshk.omnichannel.service.chatbot.flow.Flow;
import me.nageshk.omnichannel.service.chatbot.flow.FlowNode;
import me.nageshk.omnichannel.service.chatbot.flow.FlowParser;
import me.nageshk.omnichannel.service.chatbot.flow.NodeType;
import me.nageshk.omnichannel.service.chatbot.flow.settings.WaitSettings;
import org.springframework.stereotype.Component;

/** Wait (spec §4.2): a timer task of 1 minute to 30 days. */
@Component
public class WaitExecutor extends BaseExecutor {

    static final Duration MIN = Duration.ofMinutes(1);
    static final Duration MAX = Duration.ofDays(30);

    public WaitExecutor(FlowParser parser) {
        super(parser);
    }

    @Override
    public NodeType type() {
        return NodeType.WAIT;
    }

    @Override
    public Set<TaskKind> waitsFor(FlowNode node, Flow flow) {
        return Set.of(TaskKind.TIMER);
    }

    @Override
    public StepResult run(RunContext ctx, FlowNode node) {
        WaitSettings s = settings(node, WaitSettings.class);
        java.time.Instant resumeAt = ctx.now().plus(s.duration());
        return StepResult.wait(Waiting.timer(resumeAt, ctx.now()), "Waiting " + s.amount() + " " + s.unit().name().toLowerCase());
    }

    @Override
    public StepResult onAnswer(RunContext ctx, FlowNode node, TaskAnswer answer) {
        return StepResult.next(Outputs.NEXT, null, "Waited");
    }

    @Override
    public List<Problem> validate(FlowNode node, Flow flow, ValidationContext ctx) {
        WaitSettings s = settings(node, WaitSettings.class);
        Problems p = new Problems(node);
        if (s.unit() == null) p.error("unit", "is required");
        Duration d = s.duration();
        if (s.amount() <= 0 || d.compareTo(MIN) < 0 || d.compareTo(MAX) > 0) p.error("amount", "must be between 1 minute and 30 days");
        return p.list;
    }
}
