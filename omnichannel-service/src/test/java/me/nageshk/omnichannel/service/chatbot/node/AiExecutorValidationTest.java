package me.nageshk.omnichannel.service.chatbot.node;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import me.nageshk.omnichannel.ai.api.AiCapabilities;
import me.nageshk.omnichannel.ai.api.AiGateway;
import me.nageshk.omnichannel.ai.api.AiProfileInfo;
import me.nageshk.omnichannel.ai.api.AiProfileRef;
import me.nageshk.omnichannel.ai.api.StateMode;
import me.nageshk.omnichannel.ai.api.TurnRequest;
import me.nageshk.omnichannel.ai.api.TurnResult;
import me.nageshk.omnichannel.db.chatbot.TaskKind;
import me.nageshk.omnichannel.service.chatbot.flow.Flow;
import me.nageshk.omnichannel.service.chatbot.flow.FlowNode;
import me.nageshk.omnichannel.service.chatbot.flow.FlowParser;
import me.nageshk.omnichannel.service.chatbot.flow.NodeType;
import me.nageshk.omnichannel.service.chatbot.flow.Outputs;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** Spec §5.9 validation of the AI node, against a stub gateway with two profiles. */
class AiExecutorValidationTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();
    private static final AiCapabilities PLAIN = new AiCapabilities(StateMode.TRANSCRIPT, false, false, true, true,
            false, false, false);
    private static final AiCapabilities RICH = new AiCapabilities(StateMode.CHAINED, true, true, true, true,
            true, true, true);

    /** p1: a provider without temperature or reasoning effort; p2: one with both. */
    static final class StubGateway implements AiGateway {
        final Map<String, AiProfileInfo> profiles = new HashMap<>();

        StubGateway() {
            profiles.put("p1", new AiProfileInfo("p1", "Plain", "scripted", PLAIN, "m1", null, null, null, null));
            profiles.put("p2", new AiProfileInfo("p2", "Rich", "openai", RICH, "gpt", null, null, null, null));
        }

        @Override public String submit(TurnRequest r) { throw new UnsupportedOperationException(); }
        @Override public Optional<TurnResult> result(String ws, String callId) { return Optional.empty(); }
        @Override public List<AiProfileRef> profiles(String ws) { return List.of(); }

        @Override
        public Optional<AiProfileInfo> profile(String ws, String id) {
            return "w1".equals(ws) ? Optional.ofNullable(profiles.get(id)) : Optional.empty();
        }
    }

    private final FlowParser parser = new FlowParser(MAPPER);
    private final AiExecutor executor = new AiExecutor(parser, new StubGateway(), Duration.ofSeconds(120), 2);
    private final ValidationContext ctx = new ValidationContext("w1", "bot-1", false);

    private static Map<String, Object> single(Map<String, Object> extra) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("profileId", "p1");
        s.put("mode", "SINGLE");
        s.put("userMessage", "Classify: {{contact.name}}");
        s.putAll(extra);
        return s;
    }

    private static Map<String, Object> conversation(Map<String, Object> extra) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("profileId", "p1");
        s.put("mode", "CONVERSATION");
        s.putAll(extra);
        return s;
    }

    private FlowNode node(Map<String, Object> settings) {
        return new FlowNode("n1", "Classify", NodeType.AI, MAPPER.valueToTree(settings));
    }

    private static Flow flowOf(FlowNode... nodes) {
        return new Flow(List.of(nodes), List.of());
    }

    private List<Problem> check(Map<String, Object> settings) {
        FlowNode n = node(settings);
        return executor.validate(n, flowOf(n), ctx);
    }

    private static List<String> errorFields(List<Problem> problems) {
        return problems.stream().filter(p -> p.severity() == Severity.ERROR).map(Problem::field).toList();
    }

    private static Map<String, Object> outcome(String id) {
        return Map.of("id", id, "label", id.toUpperCase(), "description", "when " + id);
    }

    @Test
    void validSingleHasNoProblems() {
        assertThat(check(single(Map.of()))).isEmpty();
    }

    @Test
    void profileRequired() {
        Map<String, Object> s = single(Map.of());
        s.remove("profileId");
        assertThat(errorFields(check(s))).contains("settings.profileId");
    }

    @Test
    void unknownProfile() {
        List<Problem> problems = check(single(Map.of("profileId", "gone")));
        assertThat(problems).anySatisfy(p -> {
            assertThat(p.field()).isEqualTo("settings.profileId");
            assertThat(p.message()).isEqualTo("the AI profile no longer exists");
            assertThat(p.severity()).isEqualTo(Severity.ERROR);
        });
    }

    @Test
    void outcomeIdsValidAndUnique() {
        assertThat(errorFields(check(single(Map.of("outcomes", List.of(outcome("ok"), outcome("Bad-Id")))))))
                .containsExactly("settings.outcomes.1.id");
        assertThat(errorFields(check(single(Map.of("outcomes", List.of(outcome("a"), outcome("a")))))))
                .containsExactly("settings.outcomes.1.id");
        assertThat(errorFields(check(single(Map.of("outcomes", List.of(outcome("x".repeat(31))))))))
                .containsExactly("settings.outcomes.0.id");
        assertThat(errorFields(check(single(Map.of("outcomes", List.of(outcome("lead_hot"), outcome("cold9")))))))
                .isEmpty();
    }

    @Test
    void collectKeysValidAndUnique() {
        Map<String, Object> city = Map.of("key", "city", "description", "the city", "type", "TEXT");
        Map<String, Object> bad = Map.of("key", "9 lives", "description", "x", "type", "NUMBER");
        assertThat(errorFields(check(single(Map.of("outcomes", List.of(outcome("a")), "collect", List.of(city, bad))))))
                .containsExactly("settings.collect.1.key");
        assertThat(errorFields(check(single(Map.of("outcomes", List.of(outcome("a")), "collect", List.of(city, city))))))
                .containsExactly("settings.collect.1.key");
        assertThat(errorFields(check(single(Map.of("outcomes", List.of(outcome("a")), "collect", List.of(city))))))
                .isEmpty();
    }

    @Test
    void singleNeedsUserMessage() {
        Map<String, Object> s = single(Map.of());
        s.remove("userMessage");
        assertThat(errorFields(check(s))).containsExactly("settings.userMessage");
        // a conversation may wait for the customer's first message instead
        assertThat(errorFields(check(conversation(Map.of("exitWords", List.of("stop")))))).isEmpty();
    }

    @Test
    void jsonNeedsMessageFieldStringProperty() {
        Map<String, Object> schema = Map.of("type", "object", "properties", Map.of(
                "message", Map.of("type", "string"), "score", Map.of("type", "number")));
        Map<String, Object> noField = Map.of("type", "JSON", "schema", schema);
        Map<String, Object> numberField = Map.of("type", "JSON", "schema", schema, "messageField", "score");
        Map<String, Object> good = Map.of("type", "JSON", "schema", schema, "messageField", "message");
        // the reply is not sent: no message field needed
        assertThat(errorFields(check(single(Map.of("replyFormat", noField))))).isEmpty();
        // sent: the message field must be a string property
        assertThat(errorFields(check(single(Map.of("replyFormat", noField, "sendReply", true)))))
                .containsExactly("settings.replyFormat.messageField");
        assertThat(errorFields(check(single(Map.of("replyFormat", numberField, "sendReply", true)))))
                .containsExactly("settings.replyFormat.messageField");
        assertThat(errorFields(check(conversation(Map.of("replyFormat", noField, "exitWords", List.of("stop"))))))
                .containsExactly("settings.replyFormat.messageField");
        assertThat(errorFields(check(single(Map.of("replyFormat", good, "sendReply", true))))).isEmpty();
        // a JSON format needs a schema
        assertThat(errorFields(check(single(Map.of("replyFormat", Map.of("type", "JSON"))))))
                .containsExactly("settings.replyFormat.schema");
    }

    @Test
    void overrideAgainstCapabilities() {
        assertThat(check(single(Map.of("overrides", Map.of("temperature", 0.5))))).anySatisfy(p -> {
            assertThat(p.field()).isEqualTo("settings.overrides.temperature");
            assertThat(p.severity()).isEqualTo(Severity.ERROR);
        });
        assertThat(errorFields(check(single(Map.of("overrides", Map.of("reasoningEffort", "low"))))))
                .containsExactly("settings.overrides.reasoningEffort");
        // the rich provider takes both, within their ranges
        assertThat(errorFields(check(single(Map.of("profileId", "p2",
                "overrides", Map.of("temperature", 0.5, "reasoningEffort", "low")))))).isEmpty();
        assertThat(errorFields(check(single(Map.of("profileId", "p2",
                "overrides", Map.of("temperature", 3.0, "reasoningEffort", "huge"))))))
                .containsExactlyInAnyOrder("settings.overrides.temperature", "settings.overrides.reasoningEffort");
        assertThat(errorFields(check(single(Map.of("overrides", Map.of("model", "x".repeat(201)))))))
                .containsExactly("settings.overrides.model");
        assertThat(errorFields(check(single(Map.of("overrides", Map.of("maxOutputTokens", 0))))))
                .containsExactly("settings.overrides.maxOutputTokens");
    }

    @Test
    void maxTurnsRange() {
        assertThat(errorFields(check(conversation(Map.of("maxTurns", 0, "exitWords", List.of("stop"))))))
                .containsExactly("settings.maxTurns");
        assertThat(errorFields(check(conversation(Map.of("maxTurns", 101, "exitWords", List.of("stop"))))))
                .containsExactly("settings.maxTurns");
        assertThat(errorFields(check(conversation(Map.of("maxTurns", 100, "exitWords", List.of("stop")))))).isEmpty();
        assertThat(errorFields(check(conversation(Map.of("maxTurns", 1, "exitWords", List.of("stop")))))).isEmpty();
    }

    @Test
    void continueFromMustBeEarlierAiNode() {
        Map<String, Object> first = node("a", "ai", "Greet", single(Map.of()));
        Map<String, Object> msg = node("m", "send_message", "Msg", Map.of());
        Map<String, Object> later = node("b", "ai", "Classify", single(Map.of("continueFrom", "Greet")));
        Map<String, Object> wrong = node("c", "ai", "Other", single(Map.of("continueFrom", "Msg")));
        Map<String, Object> ahead = node("d", "ai", "Ahead", single(Map.of("continueFrom", "Classify")));
        Map<String, Object> self = node("e", "ai", "Self", single(Map.of("continueFrom", "Self")));
        Map<String, Object> f = new HashMap<>();
        f.put("nodes", List.of(first, msg, later, wrong, ahead, self));
        f.put("edges", List.of(edge("a", "next", "m"), edge("m", "next", "b"), edge("b", "next", "c"),
                edge("c", "next", "e"), edge("d", "next", "a")));
        Flow flow = parser.parse(f);
        assertThat(errorFields(executor.validate(flow.node("b").orElseThrow(), flow, ctx))).isEmpty();
        assertThat(errorFields(executor.validate(flow.node("c").orElseThrow(), flow, ctx)))
                .containsExactly("settings.continueFrom");
        assertThat(errorFields(executor.validate(flow.node("d").orElseThrow(), flow, ctx)))
                .containsExactly("settings.continueFrom");
        assertThat(errorFields(executor.validate(flow.node("e").orElseThrow(), flow, ctx)))
                .containsExactly("settings.continueFrom");
        // another provider cannot continue the conversation
        Map<String, Object> rich = node("b", "ai", "Classify", single(Map.of("continueFrom", "Greet", "profileId", "p2")));
        f.put("nodes", List.of(first, msg, rich));
        f.put("edges", List.of(edge("a", "next", "m"), edge("m", "next", "b")));
        Flow other = parser.parse(f);
        assertThat(errorFields(executor.validate(other.node("b").orElseThrow(), other, ctx)))
                .containsExactly("settings.continueFrom");
    }

    @Test
    void conversationWithoutAnyExitWarns() {
        List<Problem> problems = check(conversation(Map.of()));
        assertThat(errorFields(problems)).isEmpty();
        assertThat(problems).anySatisfy(p -> assertThat(p.severity()).isEqualTo(Severity.WARNING));
        assertThat(check(conversation(Map.of("exitWords", List.of("stop"))))).isEmpty();
        assertThat(check(conversation(Map.of("outcomes", List.of(outcome("a")))))).isEmpty();
    }

    @Test
    void exitConditionIsChecked() {
        Map<String, Object> cond = Map.of("field", "{{Classify.json.done}}", "operator", "nope", "value", "x");
        assertThat(errorFields(check(conversation(Map.of("exitCondition", cond)))))
                .containsExactly("settings.exitCondition.operator");
    }

    @Test
    void outputsPerMode() {
        assertThat(executor.outputs(node(single(Map.of())), ctx)).containsExactly(Outputs.NEXT, Outputs.FAILURE);
        assertThat(executor.outputs(node(single(Map.of("outcomes", List.of(outcome("a"), outcome("b"))))), ctx))
                .containsExactly("outcome:a", "outcome:b", Outputs.FAILURE);
        Map<String, Object> cond = Map.of("field", "{{Classify.json.done}}", "operator", "equals", "value", "yes");
        assertThat(executor.outputs(node(conversation(Map.of("outcomes", List.of(outcome("a")),
                "exitWords", List.of("stop"), "exitCondition", cond))), ctx))
                .containsExactly("outcome:a", Outputs.EXIT_WORD, Outputs.EXIT_CONDITION, Outputs.MAX_TURNS, Outputs.FAILURE);
        assertThat(executor.outputs(node(conversation(Map.of())), ctx)).containsExactly(Outputs.MAX_TURNS, Outputs.FAILURE);
        assertThat(Outputs.outcomeOf("a")).isEqualTo("outcome:a");
        assertThat(Outputs.EXIT_WORD).isEqualTo("exitWord");
        assertThat(Outputs.EXIT_CONDITION).isEqualTo("exitCondition");
        assertThat(Outputs.MAX_TURNS).isEqualTo("maxTurns");
    }

    @Test
    void waitsForPerMode() {
        FlowNode s = node(single(Map.of()));
        FlowNode c = node(conversation(Map.of()));
        assertThat(executor.waitsFor(s, flowOf(s))).isEqualTo(Set.of(TaskKind.AI_RESPONSE));
        assertThat(executor.waitsFor(c, flowOf(c))).isEqualTo(Set.of(TaskKind.WHATSAPP_REPLY, TaskKind.AI_RESPONSE));
    }

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

    static Map<String, Object> edge(String source, String handle, String target) {
        Map<String, Object> e = new HashMap<>();
        e.put("id", source + "-" + target);
        e.put("source", source);
        e.put("sourceHandle", handle);
        e.put("target", target);
        return e;
    }
}
