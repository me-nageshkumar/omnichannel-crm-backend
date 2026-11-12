package me.nageshk.omnichannel.service.chatbot.node;

import java.util.List;
import java.util.Set;
import me.nageshk.omnichannel.db.chatbot.TaskKind;
import me.nageshk.omnichannel.service.chatbot.flow.Flow;
import me.nageshk.omnichannel.service.chatbot.flow.FlowNode;
import me.nageshk.omnichannel.service.chatbot.flow.NodeType;

/**
 * One node type's behaviour (spec §4.2, §6): its outputs, what it waits for, its own validation, and how it runs and
 * takes an answer. One bean per {@link NodeType}.
 */
public interface NodeExecutor {

    NodeType type();

    /** The output ids edges may leave from, for this node's settings. */
    List<String> outputs(FlowNode node, ValidationContext ctx);

    /** The task kinds this node may wait for; empty when it never waits. */
    Set<TaskKind> waitsFor(FlowNode node, Flow flow);

    /** This node's own rules; the flow validator adds the graph rules. */
    List<Problem> validate(FlowNode node, Flow flow, ValidationContext ctx);

    StepResult run(RunContext ctx, FlowNode node);

    /** Delivers the answer to the task this node waited for. */
    default StepResult onAnswer(RunContext ctx, FlowNode node, TaskAnswer answer) {
        throw new IllegalStateException(type() + " does not wait");
    }
}
