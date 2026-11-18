package me.nageshk.omnichannel.ai.provider.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import me.nageshk.omnichannel.ai.api.AiUsage;
import me.nageshk.omnichannel.ai.provider.AiProviderException;
import me.nageshk.omnichannel.ai.provider.ProviderReply;
import me.nageshk.omnichannel.ai.provider.ToolCall;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class OpenAiRepliesTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    private ProviderReply parse(String name) throws IOException {
        try (var in = getClass().getResourceAsStream("/openai/" + name)) {
            return OpenAiReplies.parse(mapper.readTree(in));
        }
    }

    @Test
    void textReply() throws IOException {
        ProviderReply r = parse("reply-text.json");
        assertThat(r.text()).isEqualTo("Hello there");
        assertThat(r.toolCalls()).isEmpty();
        assertThat(r.responseId()).isEqualTo("resp_9");
        assertThat(r.state()).containsEntry("responseId", "resp_9");
    }

    @Test
    void functionCallReply() throws IOException {
        ProviderReply r = parse("reply-function.json");
        assertThat(r.toolCalls()).containsExactly(new ToolCall("call_1", "finish", "{\"outcome\":\"a\"}"));
        assertThat(r.usedHostedTools()).isFalse();
    }

    @Test
    void usageMapped() throws IOException {
        assertThat(parse("reply-text.json").usage()).isEqualTo(new AiUsage(10, 5, 4, 2));
    }

    @Test
    void hostedMcpCallSetsUsedHostedTools() throws IOException {
        ProviderReply r = parse("reply-mcp.json");
        assertThat(r.usedHostedTools()).isTrue();
        assertThat(r.text()).isEqualTo("ok");
    }

    @Test
    void fileAndWebSearchDoNotCount() throws IOException {
        assertThat(parse("reply-file-search.json").usedHostedTools()).isFalse();
    }

    @Test
    void completedReplyHasNoIncompleteReason() throws IOException {
        assertThat(parse("reply-text.json").incompleteReason()).isNull();
    }

    @Test
    void incompleteReplyCarriesTheReason() throws IOException {
        ProviderReply empty = parse("reply-incomplete.json");
        assertThat(empty.text()).isEmpty();
        assertThat(empty.toolCalls()).isEmpty();
        assertThat(empty.incompleteReason()).isEqualTo("max_output_tokens");
        // a cut reply that has text is still a reply
        ProviderReply cut = parse("reply-incomplete-text.json");
        assertThat(cut.text()).isEqualTo("The first part of");
        assertThat(cut.incompleteReason()).isEqualTo("max_output_tokens");
    }

    @Test
    void failedReplyThrows() {
        assertThatThrownBy(() -> parse("reply-failed.json")).isInstanceOfSatisfying(AiProviderException.class, e -> {
            assertThat(e.code()).isEqualTo(AiProviderException.Code.UNAVAILABLE);
            assertThat(e.getMessage()).contains("server_error");
        });
        assertThatThrownBy(() -> parse("reply-failed-prompt.json")).isInstanceOfSatisfying(AiProviderException.class,
                e -> assertThat(e.code()).isEqualTo(AiProviderException.Code.BAD_REQUEST));
    }

    @Test
    void refusalBecomesTheText() throws IOException {
        ProviderReply r = parse("reply-refusal.json");
        assertThat(r.text()).isEqualTo("I can't help with that.");
        assertThat(r.refused()).isTrue();
        assertThat(r.incompleteReason()).isNull();
        assertThat(parse("reply-text.json").refused()).isFalse();
    }

    @Test
    void approvalRequestThrows() {
        assertThatThrownBy(() -> parse("reply-approval.json")).isInstanceOfSatisfying(AiProviderException.class,
                e -> assertThat(e.code()).isEqualTo(AiProviderException.Code.APPROVAL_REQUIRED));
    }

    @Test
    void citationMarkersAreStripped() throws IOException {
        var root = mapper.readTree("{\"id\":\"r\",\"status\":\"completed\",\"output\":[{\"type\":\"message\","
                + "\"content\":[{\"type\":\"output_text\",\"text\":\"Fees are \u20b919,999\u30104:0\u2020fees.md\u3011.\"}]}]}");
        assertThat(OpenAiReplies.parse(root).text()).isEqualTo("Fees are \u20b919,999.");
    }
}
