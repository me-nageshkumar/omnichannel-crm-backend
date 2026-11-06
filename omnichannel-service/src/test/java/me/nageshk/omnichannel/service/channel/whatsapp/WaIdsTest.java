package me.nageshk.omnichannel.service.channel.whatsapp;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class WaIdsTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void widReadsStringSerializedDollar1AndParts() {
        assertThat(WaIds.wid(mapper.readTree("\"1@c.us\""))).isEqualTo("1@c.us");
        assertThat(WaIds.wid(mapper.readTree("{\"_serialized\":\"2@c.us\",\"user\":\"x\",\"server\":\"y\"}"))).isEqualTo("2@c.us");
        assertThat(WaIds.wid(mapper.readTree("{\"$1\":\"3@lid\"}"))).isEqualTo("3@lid");
        assertThat(WaIds.wid(mapper.readTree("{\"user\":\"4\",\"server\":\"c.us\"}"))).isEqualTo("4@c.us");
        assertThat(WaIds.wid(null)).isEmpty();
    }

    @Test
    void messageIdReadsSerializedThenDollar1ThenRebuilds() {
        assertThat(WaIds.messageId(mapper.readTree("{\"_serialized\":\"a\",\"$1\":\"b\"}"))).isEqualTo("a");
        assertThat(WaIds.messageId(mapper.readTree("{\"$1\":\"b\"}"))).isEqualTo("b");
        assertThat(WaIds.messageId(mapper.readTree("{\"fromMe\":true,\"remote\":\"1@c.us\",\"id\":\"K\"}")))
                .isEqualTo("true_1@c.us_K");
        assertThat(WaIds.messageId(mapper.readTree("{\"fromMe\":false}"))).isEmpty();
    }
}
