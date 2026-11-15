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
