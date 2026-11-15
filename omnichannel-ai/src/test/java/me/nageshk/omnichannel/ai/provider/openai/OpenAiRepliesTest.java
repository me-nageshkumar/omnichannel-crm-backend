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
    void approvalRequestThrows() {
        assertThatThrownBy(() -> parse("reply-approval.json")).isInstanceOfSatisfying(AiProviderException.class,
                e -> assertThat(e.code()).isEqualTo(AiProviderException.Code.APPROVAL_REQUIRED));
    }
}
