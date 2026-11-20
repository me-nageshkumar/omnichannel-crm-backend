package me.nageshk.omnichannel.meta.send;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import me.nageshk.omnichannel.common.channel.whatsapp.message.CloudMessage;
import me.nageshk.omnichannel.meta.api.CloudSendException;
import me.nageshk.omnichannel.meta.graph.GraphException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** The bodies Meta receives, byte for byte (spec 2026-11-20 §5.4; Meta's send reference). */
class CloudApiClientTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    private String body(String json, String recipientUserId) {
        CloudMessage m = mapper.readValue(json, CloudMessage.class);
        return mapper.writeValueAsString(CloudApiClient.body(mapper, m, recipientUserId));
    }

    @Test
    void typedText() {
        assertThat(body("{\"messaging_product\":\"whatsapp-web\",\"recipient_type\":\"individual\",\"to\":\"919799750743\","
                + "\"type\":\"text\",\"text\":{\"preview_url\":true,\"body\":\"Hi\"}}", null))
                .isEqualTo("{\"messaging_product\":\"whatsapp\",\"recipient_type\":\"individual\",\"to\":\"919799750743\","
                        + "\"type\":\"text\",\"text\":{\"preview_url\":true,\"body\":\"Hi\"}}");
    }

    @Test
    void interactiveButtonsKeepTheirShape() {
        String interactive = "{\"type\":\"button\",\"body\":{\"text\":\"Book a demo?\"},\"action\":{\"buttons\":["
                + "{\"type\":\"reply\",\"reply\":{\"id\":\"yes\",\"title\":\"Yes\"}}]}}";
        String out = body("{\"messaging_product\":\"whatsapp\",\"to\":\"919799750743\",\"type\":\"interactive\","
                + "\"interactive\":" + interactive + "}", null);
        assertThat(out).startsWith("{\"messaging_product\":\"whatsapp\",\"recipient_type\":\"individual\","
                + "\"to\":\"919799750743\",\"type\":\"interactive\",\"interactive\":");
        assertThat(mapper.readTree(out).get("interactive")).isEqualTo(mapper.readTree(interactive));
    }

    @Test
    void imageById() {
        assertThat(body("{\"messaging_product\":\"whatsapp\",\"to\":\"919799750743\",\"type\":\"image\","
                + "\"image\":{\"id\":\"m1\",\"caption\":\"c\"}}", null))
                .isEqualTo("{\"messaging_product\":\"whatsapp\",\"recipient_type\":\"individual\",\"to\":\"919799750743\","
                        + "\"type\":\"image\",\"image\":{\"id\":\"m1\",\"caption\":\"c\"}}");
    }

    @Test
    void replyCarriesContext() {
        assertThat(mapper.readTree(body("{\"messaging_product\":\"whatsapp\",\"to\":\"919799750743\",\"type\":\"text\","
                + "\"text\":{\"body\":\"ok\"},\"context\":{\"message_id\":\"wamid.A\"}}", null)).get("context")
                .get("message_id").asString()).isEqualTo("wamid.A");
    }

    @Test
    void userIdRecipientReplacesTo() {
        String out = body("{\"messaging_product\":\"whatsapp\",\"to\":\"US.123@user\",\"type\":\"text\","
                + "\"text\":{\"body\":\"Hi\"}}", "US.123");
        assertThat(mapper.readTree(out).has("to")).isFalse();
        assertThat(mapper.readTree(out).get("recipient").asString()).isEqualTo("US.123");
    }

    @Test
    void graphErrorsMapToSendKinds() {
        assertThat(CloudApiClient.of(new GraphException(GraphException.Kind.AUTH, 401, 190, "t", null, null)).kind())
                .isEqualTo(CloudSendException.Kind.TOKEN_REFUSED);
        assertThat(CloudApiClient.of(new GraphException(GraphException.Kind.WINDOW_CLOSED, 400, 131047, "t", null,
                null)).kind()).isEqualTo(CloudSendException.Kind.WINDOW_CLOSED);
        CloudSendException rate = CloudApiClient.of(new GraphException(GraphException.Kind.RATE_LIMIT, 429, 130429, "t",
                null, Duration.ofSeconds(3)));
        assertThat(rate.kind()).isEqualTo(CloudSendException.Kind.RATE_LIMITED);
        assertThat(rate.retryAfter()).isEqualTo(Duration.ofSeconds(3));
        assertThat(CloudApiClient.of(new GraphException(GraphException.Kind.BAD_REQUEST, 400, 100, "t", "d", null))
                .definitelyNotSent()).isTrue();
        CloudSendException timeout = CloudApiClient.of(new GraphException(GraphException.Kind.TIMEOUT, 0, null, "t",
                null, null));
        assertThat(timeout.kind()).isEqualTo(CloudSendException.Kind.UNKNOWN);
        assertThat(timeout.definitelyNotSent()).isFalse();
    }
}
