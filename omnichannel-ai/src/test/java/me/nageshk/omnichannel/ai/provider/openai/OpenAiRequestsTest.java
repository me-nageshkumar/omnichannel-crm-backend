package me.nageshk.omnichannel.ai.provider.openai;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import me.nageshk.omnichannel.ai.api.AiReplyFormat;
import me.nageshk.omnichannel.ai.provider.AiItem;
import me.nageshk.omnichannel.ai.provider.AiTool;
import me.nageshk.omnichannel.ai.provider.ProviderRequest;
import me.nageshk.omnichannel.ai.provider.ToolChoice;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class OpenAiRequestsTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    private JsonNode golden(String name) throws IOException {
        try (var in = getClass().getResourceAsStream("/openai/" + name)) {
            return mapper.readTree(in);
        }
    }

    private JsonNode body(ProviderRequest r) {
        return mapper.valueToTree(OpenAiRequests.body(r));
    }

    private static ProviderRequest req(String instructions, List<AiItem> input, Map<String, Object> state,
            List<AiTool> tools, ToolChoice choice, AiReplyFormat format, String effort, Integer max, Double temp,
            Map<String, String> metadata) {
        return new ProviderRequest("gpt-5", instructions, input, state, tools, choice, format, effort, max, temp,
                metadata);
    }

    @Test
    void firstTurn() throws IOException {
        var r = req("Be brief.", List.of(new AiItem.UserText("Hi")), null, null, null, null, null, null, null,
                Map.of("runId", "r1", "nodeName", "AI"));
        assertThat(body(r)).isEqualTo(golden("first-turn.json"));
    }

    @Test
    void chainedTurnSendsPreviousIdAndInstructionsAgain() throws IOException {
        var r = req("Be brief.", List.of(new AiItem.UserText("Again")), Map.of("responseId", "resp_1"), List.of(),
                null, null, null, null, null, Map.of());
        assertThat(body(r)).isEqualTo(golden("chained-turn.json"));
    }

    @Test
    void toolsMapping() throws IOException {
        var tools = List.<AiTool>of(
                new AiTool.FunctionTool("finish", "End it",
                        Map.of("type", "object", "properties", Map.of("outcome", Map.of("type", "string")))),
                new AiTool.HostedMcpTool("crm", "https://mcp.example.com", Map.of("X-Key", "v"), List.of("a", "b")),
                new AiTool.FileSearchTool(List.of("vs_1")),
                new AiTool.WebSearchTool());
        var r = req(null, List.of(new AiItem.UserText("Hi")), null, tools,
                new ToolChoice(ToolChoice.Mode.NAMED, "finish"), null, null, null, null, null);
        assertThat(body(r)).isEqualTo(golden("tools.json"));
    }

    @Test
    void strictOnlyWhenTheSchemaAllowsIt() {
        Map<String, Object> ready = Map.of("type", "object", "additionalProperties", false,
                "required", List.of("outcome", "fields"),
                "properties", Map.of("outcome", Map.of("type", "string"),
                        "fields", Map.of("type", "object", "additionalProperties", false, "required", List.of("city"),
                                "properties", Map.of("city", Map.of("type", List.of("string", "null")))),
                        "tags", Map.of("type", "array", "items", Map.of("type", "string"))));
        Map<String, Object> readyAll = new java.util.LinkedHashMap<>(ready);
        readyAll.put("required", List.of("outcome", "fields", "tags"));
        Map<String, Object> optionalParam = Map.of("type", "object",
                "properties", Map.of("id", Map.of("type", "integer"), "note", Map.of("type", "string")),
                "required", List.of("id"));
        Map<String, Object> nestedOpen = Map.of("type", "object", "additionalProperties", false,
                "required", List.of("filter"),
                "properties", Map.of("filter", Map.of("type", "object", "properties", Map.of())));
        var tools = List.<AiTool>of(new AiTool.FunctionTool("a", null, readyAll),
                new AiTool.FunctionTool("b", null, ready), new AiTool.FunctionTool("c", null, optionalParam),
                new AiTool.FunctionTool("d", null, nestedOpen), new AiTool.FunctionTool("e", null, Map.of("type", "object")));
        var r = req(null, List.of(new AiItem.UserText("Hi")), null, tools, null, null, null, null, null, null);

        JsonNode sent = body(r).path("tools");
        assertThat(List.of(0, 1, 2, 3, 4)).extracting(i -> sent.get(i).path("strict").asBoolean())
                .containsExactly(true, false, false, false, false);
        var finish = me.nageshk.omnichannel.ai.turn.FinishTool.of(
                List.of(new me.nageshk.omnichannel.ai.api.AiOutcome("a", "A", null)),
                List.of(new me.nageshk.omnichannel.ai.api.AiField("city", null, me.nageshk.omnichannel.ai.api.AiFieldType.TEXT)))
                .orElseThrow();
        assertThat(OpenAiRequests.strictReady(finish.parameters())).as("the finish tool is strict").isTrue();
    }

    @Test
    void toolResultItem() throws IOException {
        var input = List.<AiItem>of(new AiItem.UserText("Hi"),
                new AiItem.ToolCallItem("c1", "finish", "{\"outcome\":\"a\"}"),
                new AiItem.ToolResultItem("c1", "finished"), new AiItem.AssistantText("Done"));
        var r = req(null, input, null, null, null, null, null, null, null, null);
        assertThat(body(r)).isEqualTo(golden("tool-result.json"));
    }

    @Test
    void jsonFormat() throws IOException {
        var r = req(null, List.of(new AiItem.UserText("Hi")), null, null,
                new ToolChoice(ToolChoice.Mode.REQUIRED, null),
                new AiReplyFormat(AiReplyFormat.Type.JSON, Map.of("type", "object"), "reply"), "low", 500, 0.2, null);
        assertThat(body(r)).isEqualTo(golden("json-format.json"));
    }

    @Test
    void jsonFormatStrictOnlyWhenTheSchemaAllowsIt() {
        Map<String, Object> open = Map.of("type", "object", "properties", Map.of("message", Map.of("type", "string")));
        Map<String, Object> closed = Map.of("type", "object", "additionalProperties", false,
                "required", List.of("message"), "properties", Map.of("message", Map.of("type", "string")));
        JsonNode loose = body(req(null, List.of(new AiItem.UserText("Hi")), null, null, null,
                new AiReplyFormat(AiReplyFormat.Type.JSON, open, "message"), null, null, null, null));
        JsonNode strict = body(req(null, List.of(new AiItem.UserText("Hi")), null, null, null,
                new AiReplyFormat(AiReplyFormat.Type.JSON, closed, "message"), null, null, null, null));
        // strict mode refuses a schema that is not closed and fully required: OpenAI would answer 400 on every turn
        assertThat(loose.path("text").path("format").path("strict").asBoolean()).isFalse();
        assertThat(loose.path("text").path("format").path("schema")).isEqualTo(mapper.valueToTree(open));
        assertThat(strict.path("text").path("format").path("strict").asBoolean()).isTrue();
    }

    @Test
    void nullOverridesOmitted() {
        var r = req(null, List.of(new AiItem.UserText("Hi")), null, null, new ToolChoice(ToolChoice.Mode.AUTO, null),
                new AiReplyFormat(AiReplyFormat.Type.TEXT, null, null), null, null, null, null);
        JsonNode n = body(r);
        for (String f : List.of("instructions", "previous_response_id", "tools", "text", "reasoning",
                "max_output_tokens", "temperature", "metadata")) {
            assertThat(n.has(f)).as(f).isFalse();
        }
        assertThat(n.get("tool_choice").asString()).isEqualTo("auto");
    }
}
