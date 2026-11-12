package me.nageshk.omnichannel.common.channel.whatsapp.message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import me.nageshk.omnichannel.common.api.ValidationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** The Cloud API message objects round-trip Meta's documented examples unchanged (spec §3, §5.1). */
class CloudMessageJsonTest {

    private static final ObjectMapper mapper = JsonMapper.builder().build();
    private final CloudMessages cloud = new CloudMessages(mapper);

    @ParameterizedTest
    @ValueSource(strings = {"text", "button", "list", "cta_url", "location_request"})
    void roundTripsMetaExamplesByteEqual(String name) throws Exception {
        JsonNode original = mapper.readTree(getClass().getResourceAsStream("/cloud/" + name + ".json"));
        CloudMessage parsed = cloud.parse(original);
        JsonNode back = mapper.valueToTree(cloud.toMap(parsed));
        assertThat(back).isEqualTo(original);
    }

    @Test
    void unknownKeyIsRejected() {
        Map<String, Object> m = Map.of("messaging_product", "whatsapp-web", "type", "text",
                "text", Map.of("body", "hi"), "surprise", 1);
        assertThatThrownBy(() -> cloud.fromMap(m)).isInstanceOf(ValidationException.class)
                .satisfies(e -> assertThat(((ValidationException) e).fields())
                        .containsEntry("message", "unknown field surprise"));
    }

    @Test
    void nestedUnknownKeyIsRejected() {
        Map<String, Object> m = Map.of("messaging_product", "whatsapp-web", "type", "text",
                "text", Map.of("body", "hi", "colour", "red"));
        assertThatThrownBy(() -> cloud.fromMap(m)).isInstanceOf(ValidationException.class)
                .satisfies(e -> assertThat(((ValidationException) e).fields())
                        .containsEntry("message", "unknown field colour"));
    }

    @Test
    void nullsAreOmitted() {
        CloudMessage m = new CloudMessage("whatsapp-web", null, "1", "text", new Text(null, "hi"), null, null);
        assertThat(cloud.toMap(m)).containsOnlyKeys("messaging_product", "to", "type", "text");
    }

    @Test
    void limitsAreMetas() {
        assertThat(CloudMessages.Limits.BUTTONS).isEqualTo(3);
        assertThat(CloudMessages.Limits.ROWS).isEqualTo(10);
        assertThat(CloudMessages.Limits.BODY_INTERACTIVE).isEqualTo(1024);
        assertThat(CloudMessages.Limits.BODY_LIST).isEqualTo(4096);
    }
}
