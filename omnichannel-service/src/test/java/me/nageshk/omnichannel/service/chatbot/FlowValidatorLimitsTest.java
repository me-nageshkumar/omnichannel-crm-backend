package me.nageshk.omnichannel.service.chatbot;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.nageshk.omnichannel.db.chatbot.TaskKind;
import me.nageshk.omnichannel.service.chatbot.flow.FlowParser;
import me.nageshk.omnichannel.service.chatbot.flow.NodeType;
import me.nageshk.omnichannel.service.chatbot.flow.Outputs;
import me.nageshk.omnichannel.service.chatbot.node.NodeExecutors;
import me.nageshk.omnichannel.service.chatbot.node.Problem;
import me.nageshk.omnichannel.service.chatbot.node.Severity;
import me.nageshk.omnichannel.service.chatbot.node.ValidationContext;
import me.nageshk.omnichannel.service.chatbot.run.TaskAnswerSources;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** A plan's caps on the size of one chatbot. */
class FlowValidatorLimitsTest {

    private final FlowParser parser = new FlowParser(JsonMapper.builder().build());
    private final FlowValidator validator = new FlowValidator(new NodeExecutors(List.of(
            new FlowValidatorTest.Stub(NodeType.WAIT, List.of(Outputs.NEXT), Set.of(TaskKind.TIMER)),
            new FlowValidatorTest.Stub(NodeType.AI, List.of(Outputs.NEXT), Set.of()))),
            new TaskAnswerSources(List.of(() -> TaskKind.TIMER)));

    private List<String> planProblems(Integer maxNodes, Integer maxAiNodes) {
        Map<String, Object> f = new HashMap<>();
        f.put("nodes", List.of(FlowValidatorTest.node("a", "wait", "A", Map.of()),
                FlowValidatorTest.node("b", "ai", "B", Map.of()), FlowValidatorTest.node("c", "ai", "C", Map.of())));
        f.put("edges", List.of(FlowValidatorTest.edge("e1", "a", null, "b"), FlowValidatorTest.edge("e2", "b", null, "c")));
        List<Problem> all = validator.validate(parser.parse(f), new ValidationContext("w1", "bot-1", false, maxNodes, maxAiNodes));
        return all.stream().filter(p -> p.severity() == Severity.ERROR && "plan".equals(p.field()))
                .map(Problem::message).toList();
    }

    @Test
    void aFlowAtTheCapIsFine() {
        assertThat(planProblems(3, 2)).isEmpty();
        assertThat(planProblems(null, null)).isEmpty();
    }

    @Test
    void tooManyNodesIsAnError() {
        assertThat(planProblems(2, null)).containsExactly("Your plan allows 2 nodes in a chatbot");
    }

    @Test
    void tooManyAiNodesIsAnError() {
        assertThat(planProblems(null, 1)).containsExactly("Your plan allows 1 AI nodes in a chatbot");
    }

    @Test
    void theOldContextHasNoCaps() {
        ValidationContext ctx = new ValidationContext("w1", "bot-1", false);
        assertThat(ctx.maxNodes()).isNull();
        assertThat(ctx.maxAiNodes()).isNull();
    }
}
