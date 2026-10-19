package me.nageshk.omnichannel.events;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class LoggingEventListenerTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void widAcceptsStringsAndWidObjects() throws Exception {
        assertThat(LoggingEventListener.wid(mapper.readTree("\"1@c.us\""))).isEqualTo("1@c.us");
        assertThat(LoggingEventListener.wid(mapper.readTree("{\"server\":\"lid\",\"user\":\"115\",\"_serialized\":\"115@lid\"}")))
                .isEqualTo("115@lid");
        assertThat(LoggingEventListener.wid(mapper.readTree("{\"server\":\"c.us\",\"user\":\"9\"}"))).isEqualTo("9@c.us");
        assertThat(LoggingEventListener.wid(mapper.missingNode())).isEqualTo("");
    }

    @Test
    void messageIdUsesSerializedOrRebuildsFromParts() throws Exception {
        JsonNode withSerialized = mapper.readTree("{\"fromMe\":true,\"remote\":\"1@c.us\",\"id\":\"A\",\"_serialized\":\"true_1@c.us_A\"}");
        assertThat(LoggingEventListener.messageId(withSerialized)).isEqualTo("true_1@c.us_A");

        JsonNode mangled = mapper.readTree("{\"fromMe\":true,\"remote\":\"103@lid\",\"id\":\"3EB0\",\"$1\":\"true_103@lid_3EB0\"}");
        assertThat(LoggingEventListener.messageId(mangled)).isEqualTo("true_103@lid_3EB0");

        assertThat(LoggingEventListener.messageId(mapper.readTree("{}"))).isEqualTo("");
    }
}
