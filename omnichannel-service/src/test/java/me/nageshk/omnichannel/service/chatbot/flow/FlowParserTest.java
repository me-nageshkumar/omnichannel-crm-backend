package me.nageshk.omnichannel.service.chatbot.flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import me.nageshk.omnichannel.common.api.ValidationException;
import me.nageshk.omnichannel.service.chatbot.flow.settings.WaitSettings;
import me.nageshk.omnichannel.service.chatbot.flow.settings.WaitUnit;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Spec §4.1: the canvas flow is kept verbatim; the server reads ids, types, names, settings and edges. */
class FlowParserTest {

    private final FlowParser parser = new FlowParser(JsonMapper.builder().build());

    static Map<String, Object> node(String id, String type, String name, Map<String, Object> settings) {
        Map<String, Object> data = new HashMap<>();
        data.put("name", name);
        data.put("settings", settings);
        Map<String, Object> n = new HashMap<>();
        n.put("id", id);
        n.put("type", type);
        n.put("position", Map.of("x", 10, "y", 20));
        n.put("measured", Map.of("width", 300, "height", 120));
        n.put("selected", true);
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

    static Map<String, Object> flow(List<Map<String, Object>> nodes, List<Map<String, Object>> edges) {
        Map<String, Object> f = new HashMap<>();
        f.put("nodes", nodes);
        f.put("edges", edges);
        f.put("viewport", Map.of("x", 0, "y", 0, "zoom", 1));
        return f;
    }

    private Map<String, Object> twoNodes() {
        return flow(List.of(node("n1", "send_message", "Welcome", Map.of("message", Map.of("type", "text"))),
                node("n2", "wait", "Pause", Map.of("amount", 2, "unit", "HOURS"))),
                List.of(edge("e1", "n1", "reply:yes", "n2"), edge("e2", "n2", null, "n1")));
    }

    @Test
    void readsNodesEdgesAndHandles() {
        Flow f = parser.parse(twoNodes());
        assertThat(f.nodes()).extracting(FlowNode::id).containsExactly("n1", "n2");
        assertThat(f.node("n1").orElseThrow().type()).isEqualTo(NodeType.SEND_MESSAGE);
        assertThat(f.node("n1").orElseThrow().name()).isEqualTo("Welcome");
        assertThat(f.target("n1", "reply:yes")).isEqualTo(Optional.of("n2"));
        assertThat(f.target("n2", Outputs.NEXT)).isEqualTo(Optional.of("n1"));
        assertThat(f.from("n1")).extracting(FlowEdge::output).containsExactly("reply:yes");
    }

    @Test
    void ignoresCanvasOnlyKeys() {
        assertThat(parser.parse(twoNodes()).nodes()).hasSize(2);
    }

    @Test
    void refusesUnknownType() {
        Map<String, Object> f = flow(List.of(node("n1", "open_ai", "Ai", Map.of())), List.of());
        assertThatThrownBy(() -> parser.parse(f)).isInstanceOf(ValidationException.class)
                .satisfies(e -> assertThat(((ValidationException) e).fields().get("flow")).contains("open_ai"));
    }

    @Test
    void refusesDuplicateIds() {
        Map<String, Object> f = flow(List.of(node("n1", "wait", "A", Map.of()), node("n1", "wait", "B", Map.of())), List.of());
        assertThatThrownBy(() -> parser.parse(f)).isInstanceOf(ValidationException.class)
                .satisfies(e -> assertThat(((ValidationException) e).fields()).containsKey("flow"));
    }

    @Test
    void refusesDanglingEdge() {
        Map<String, Object> f = flow(List.of(node("n1", "wait", "A", Map.of())), List.of(edge("e1", "n1", null, "gone")));
        assertThatThrownBy(() -> parser.parse(f)).isInstanceOf(ValidationException.class)
                .satisfies(e -> assertThat(((ValidationException) e).fields().get("flow")).contains("gone"));
    }

    @Test
    void refusesMissingNodesArray() {
        assertThatThrownBy(() -> parser.parse(Map.of("edges", List.of()))).isInstanceOf(ValidationException.class);
    }

    @Test
    void entryCandidatesAreNodesWithoutIncomingEdges() {
        assertThat(parser.parse(twoNodes()).entryCandidates()).isEmpty();
        Map<String, Object> f = flow(List.of(node("a", "wait", "A", Map.of()), node("b", "wait", "B", Map.of())),
                List.of(edge("e", "a", null, "b")));
        assertThat(parser.parse(f).entryCandidates()).containsExactly("a");
    }

    @Test
    void settingsConvertByType() {
        Flow f = parser.parse(twoNodes());
        WaitSettings w = parser.settings(f.node("n2").orElseThrow(), WaitSettings.class);
        assertThat(w.unit()).isEqualTo(WaitUnit.HOURS);
        assertThat(w.duration()).isEqualTo(Duration.ofHours(2));
        Map<String, Object> bad = flow(List.of(node("n2", "wait", "Pause", Map.of("amount", "two", "unit", "HOURS"))), List.of());
        FlowNode n = parser.parse(bad).node("n2").orElseThrow();
        assertThatThrownBy(() -> parser.settings(n, WaitSettings.class)).isInstanceOf(ValidationException.class)
                .satisfies(e -> assertThat(((ValidationException) e).fields()).containsKey("nodes.n2.settings"));
    }

    @Test
    void settingsKeepUiOnlyKeys() {
        Map<String, Object> s = new HashMap<>(Map.of("amount", 1, "unit", "DAYS", "collapsed", true));
        Flow f = parser.parse(flow(List.of(node("n", "wait", "W", s)), new ArrayList<>()));
        assertThat(parser.settings(f.node("n").orElseThrow(), WaitSettings.class).amount()).isEqualTo(1);
    }

    @Test
    void nodeTypeCodes() {
        assertThat(NodeType.of("ask_form")).contains(NodeType.ASK_FORM);
        assertThat(NodeType.ASK_QUESTION.code()).isEqualTo("ask_question");
        assertThat(NodeType.of("nope")).isEmpty();
        assertThat(Outputs.reply("yes")).isEqualTo("reply:yes");
        assertThat(Outputs.caseOf("c1")).isEqualTo("case:c1");
    }
}
