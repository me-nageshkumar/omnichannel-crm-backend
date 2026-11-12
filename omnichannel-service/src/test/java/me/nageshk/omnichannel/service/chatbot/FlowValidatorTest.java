package me.nageshk.omnichannel.service.chatbot;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.nageshk.omnichannel.db.chatbot.TaskKind;
import me.nageshk.omnichannel.service.chatbot.flow.Flow;
import me.nageshk.omnichannel.service.chatbot.flow.FlowNode;
import me.nageshk.omnichannel.service.chatbot.flow.FlowParser;
import me.nageshk.omnichannel.service.chatbot.flow.NodeType;
import me.nageshk.omnichannel.service.chatbot.flow.Outputs;
import me.nageshk.omnichannel.service.chatbot.node.NodeExecutor;
import me.nageshk.omnichannel.service.chatbot.node.NodeExecutors;
import me.nageshk.omnichannel.service.chatbot.node.Problem;
import me.nageshk.omnichannel.service.chatbot.node.RunContext;
import me.nageshk.omnichannel.service.chatbot.node.Severity;
import me.nageshk.omnichannel.service.chatbot.node.StepResult;
import me.nageshk.omnichannel.service.chatbot.node.ValidationContext;
import me.nageshk.omnichannel.service.chatbot.run.TaskAnswerSources;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Spec §6 flow checks, with stub executors so only the graph rules are under test. */
class FlowValidatorTest {

    /** A stub: fixed outputs and waits, no settings rules. */
    record Stub(NodeType type, List<String> outputs, Set<TaskKind> waits) implements NodeExecutor {
        @Override public List<String> outputs(FlowNode node, ValidationContext ctx) { return outputs; }
        @Override public Set<TaskKind> waitsFor(FlowNode node, Flow flow) { return waits; }
        @Override public List<Problem> validate(FlowNode node, Flow flow, ValidationContext ctx) { return List.of(); }
        @Override public StepResult run(RunContext ctx, FlowNode node) { throw new UnsupportedOperationException(); }
    }

    private final FlowParser parser = new FlowParser(JsonMapper.builder().build());
    private final NodeExecutors executors = new NodeExecutors(List.of(
            new Stub(NodeType.SEND_MESSAGE, List.of(Outputs.reply("yes"), Outputs.reply("no"), Outputs.OTHER),
                    Set.of(TaskKind.WHATSAPP_REPLY)),
            new Stub(NodeType.WAIT, List.of(Outputs.NEXT), Set.of(TaskKind.TIMER)),
            new Stub(NodeType.UPDATE_CONTACT, List.of(Outputs.NEXT), Set.of()),
            new Stub(NodeType.ASK_FORM, List.of(Outputs.NEXT), Set.of(TaskKind.FORM)),
            new Stub(NodeType.JUMP, List.of(), Set.of())));
    private final FlowValidator validator = new FlowValidator(executors,
            new TaskAnswerSources(List.of(() -> TaskKind.WHATSAPP_REPLY, () -> TaskKind.TIMER)));
    private final ValidationContext ctx = new ValidationContext("w1", "bot-1", false);

    static Map<String, Object> node(String id, String type, String name, Map<String, Object> settings) {
        Map<String, Object> n = new HashMap<>();
        n.put("id", id);
        n.put("type", type);
        Map<String, Object> data = new HashMap<>();
        data.put("name", name);
        data.put("settings", settings);
        n.put("data", data);
        return n;
    }

    static Map<String, Object> edge(String id, String source, String handle, String target) {
        Map<String, Object> e = new HashMap<>();
        e.put("id", id);
        e.put("source", source);
        if (handle != null) e.put("sourceHandle", handle);
        e.put("target", target);
        return e;
    }

    private List<Problem> check(List<Map<String, Object>> nodes, List<Map<String, Object>> edges) {
        Map<String, Object> f = new HashMap<>();
        f.put("nodes", nodes);
        f.put("edges", edges);
        return validator.validate(parser.parse(f), ctx);
    }

    private static List<Problem> errors(List<Problem> all) {
        return all.stream().filter(p -> p.severity() == Severity.ERROR).toList();
    }

    @Test
    void emptyFlowIsAnError() {
        assertThat(errors(check(List.of(), List.of()))).extracting(Problem::field).contains("nodes");
    }

    @Test
    void needsExactlyOneEntry() {
        List<Problem> cycle = check(List.of(node("a", "wait", "A", Map.of()), node("b", "wait", "B", Map.of())),
                List.of(edge("e1", "a", null, "b"), edge("e2", "b", null, "a")));
        assertThat(errors(cycle)).anySatisfy(p -> {
            assertThat(p.field()).isEqualTo("entry");
            assertThat(p.message()).contains("no entry node");
        });
        List<Problem> twoRoots = check(List.of(node("a", "wait", "A", Map.of()), node("b", "wait", "B", Map.of())), List.of());
        assertThat(errors(twoRoots)).anySatisfy(p -> assertThat(p.field()).isEqualTo("entry"));
        assertThat(errors(check(List.of(node("a", "wait", "A", Map.of())), List.of()))).isEmpty();
    }

    @Test
    void unreachableNodeIsWarning() {
        List<Problem> p = check(List.of(node("a", "wait", "A", Map.of()), node("b", "wait", "B", Map.of()),
                node("c", "wait", "C", Map.of())), List.of(edge("e1", "a", null, "b"), edge("e2", "c", null, "b")));
        // two roots (a and c) is an error; and nothing else: c is a root, not unreachable
        assertThat(errors(p)).extracting(Problem::field).containsExactly("entry");
        List<Problem> q = check(List.of(node("a", "wait", "A", Map.of()), node("b", "wait", "B", Map.of()),
                node("c", "wait", "C", Map.of()), node("d", "wait", "D", Map.of())),
                List.of(edge("e1", "a", null, "b"), edge("e2", "c", null, "d"), edge("e3", "d", null, "c")));
        assertThat(errors(q)).isEmpty();
        assertThat(q).filteredOn(x -> x.severity() == Severity.WARNING).extracting(Problem::nodeId)
                .containsExactlyInAnyOrder("c", "d");
    }

    @Test
    void edgeFromUnknownOutputIsError() {
        List<Problem> p = check(List.of(node("a", "send_message", "A", Map.of()), node("b", "wait", "B", Map.of())),
                List.of(edge("e1", "a", "reply:gone", "b")));
        assertThat(errors(p)).anySatisfy(x -> {
            assertThat(x.field()).isEqualTo("edges.e1");
            assertThat(x.nodeId()).isEqualTo("a");
        });
    }

    @Test
    void twoEdgesFromOneOutputIsError() {
        List<Problem> p = check(List.of(node("a", "wait", "A", Map.of()), node("b", "wait", "B", Map.of()),
                node("c", "wait", "C", Map.of())), List.of(edge("e1", "a", null, "b"), edge("e2", "a", null, "c")));
        assertThat(errors(p)).anySatisfy(x -> assertThat(x.field()).isEqualTo("edges.e2"));
    }

    @Test
    void badNameIsError() {
        assertThat(errors(check(List.of(node("a", "wait", "Ask budget", Map.of())), List.of())))
                .anySatisfy(x -> assertThat(x.field()).isEqualTo("name"));
        assertThat(errors(check(List.of(node("a", "wait", null, Map.of())), List.of())))
                .anySatisfy(x -> assertThat(x.field()).isEqualTo("name"));
    }

    @Test
    void duplicateNameIsError() {
        List<Problem> p = check(List.of(node("a", "wait", "Same", Map.of()), node("b", "wait", "same", Map.of())),
                List.of(edge("e1", "a", null, "b")));
        assertThat(errors(p)).anySatisfy(x -> {
            assertThat(x.field()).isEqualTo("name");
            assertThat(x.nodeId()).isEqualTo("b");
        });
    }

    @Test
    void unknownVariableIsWarning() {
        List<Problem> p = check(List.of(node("a", "update_contact", "A", Map.of("nameIfBlank", "{{Nope.answer}} {{contact.name}} {{A.x}}"))),
                List.of());
        assertThat(p).filteredOn(x -> x.severity() == Severity.WARNING).anySatisfy(x -> {
            assertThat(x.field()).isEqualTo("settings.nameIfBlank");
            assertThat(x.message()).contains("Nope");
        });
        assertThat(errors(p)).isEmpty();
    }

    @Test
    void jumpToSelfIsError() {
        List<Problem> p = check(List.of(node("a", "jump", "A", Map.of("chatbotId", "bot-1"))), List.of());
        assertThat(errors(p)).anySatisfy(x -> assertThat(x.field()).isEqualTo("settings.chatbotId"));
    }

    @Test
    void waitingForAnUnansweredKindIsError() {
        List<Problem> p = check(List.of(node("a", "ask_form", "A", Map.of())), List.of());
        assertThat(errors(p)).anySatisfy(x -> {
            assertThat(x.field()).isEqualTo("settings");
            assertThat(x.message()).contains("FORM");
        });
    }

    @Test
    void publishableOnlyWithoutErrors() {
        List<Problem> clean = check(List.of(node("a", "wait", "A", Map.of())), List.of());
        assertThat(FlowValidator.publishable(clean)).isTrue();
        assertThat(FlowValidator.publishable(check(List.of(), List.of()))).isFalse();
        List<Problem> warn = new ArrayList<>();
        warn.add(Problem.warning(new FlowNode("x", "X", NodeType.WAIT, null), "settings", "meh"));
        assertThat(FlowValidator.publishable(warn)).isTrue();
        assertThat(EnumSet.allOf(TaskKind.class)).hasSize(3);
    }
}
