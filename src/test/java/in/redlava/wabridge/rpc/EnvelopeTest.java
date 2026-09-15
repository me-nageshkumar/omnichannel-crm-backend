package in.redlava.wabridge.rpc;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

class EnvelopeTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void parsesRequestFrame() throws Exception {
        String json = "{\"type\":\"request\",\"id\":\"abc\",\"method\":\"sendMessage\",\"params\":{\"chatId\":\"1@c.us\"}}";
        Envelope env = mapper.readValue(json, Envelope.class);

        assertThat(env.type()).isEqualTo(Envelope.REQUEST);
        assertThat(env.id()).isEqualTo("abc");
        assertThat(env.method()).isEqualTo("sendMessage");
        assertThat(env.params().get("chatId").asText()).isEqualTo("1@c.us");
    }

    @Test
    void parsesErrorResponseAndIgnoresUnknownFields() throws Exception {
        String json = "{\"type\":\"response\",\"id\":\"abc\",\"error\":{\"code\":\"PAGE_ERROR\",\"message\":\"boom\"},\"extra\":1}";
        Envelope env = mapper.readValue(json, Envelope.class);

        assertThat(env.error().code()).isEqualTo("PAGE_ERROR");
        assertThat(env.error().message()).isEqualTo("boom");
        assertThat(env.result()).isNull();
    }

    @Test
    void serializesWithoutNullFields() throws Exception {
        ObjectNode params = mapper.createObjectNode().put("chatId", "1@c.us");
        String json = mapper.writeValueAsString(Envelope.request("id1", "sendMessage", params));

        assertThat(json).contains("\"type\":\"request\"").contains("\"method\":\"sendMessage\"");
        assertThat(json).doesNotContain("token").doesNotContain("error").doesNotContain("payload");
    }

    @Test
    void factoriesProduceExpectedTypes() {
        assertThat(Envelope.auth("t").type()).isEqualTo(Envelope.AUTH);
        assertThat(Envelope.auth("t").token()).isEqualTo("t");
        assertThat(Envelope.ping().type()).isEqualTo(Envelope.PING);
        assertThat(Envelope.pong().type()).isEqualTo(Envelope.PONG);
        assertThat(Envelope.event("message", null).name()).isEqualTo("message");
        assertThat(Envelope.errorResponse("x", "CHAT_NOT_FOUND", "nope").error().code()).isEqualTo("CHAT_NOT_FOUND");
    }
}
