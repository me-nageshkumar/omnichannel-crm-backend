package me.nageshk.omnichannel.app.ws;

import static org.assertj.core.api.Assertions.assertThat;

import me.nageshk.omnichannel.app.ws.NumberCheck.Result;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class NumberCheckTest {

    final ObjectMapper mapper = new ObjectMapper();

    private ObjectNode info(String wid, String pnWid) {
        ObjectNode n = mapper.createObjectNode();
        if (wid != null) n.put("wid", wid);
        if (pnWid != null) n.put("pnWid", pnWid);
        return n;
    }

    @Test
    void pnWidWins() {
        NumberCheck c = NumberCheck.check("919928088743", info("1234567@lid", "919928088743@c.us"));
        assertThat(c.result()).isEqualTo(Result.MATCH);
        assertThat(c.reported()).isEqualTo("919928088743");
    }

    @Test
    void cUsWidIsUsed() {
        assertThat(NumberCheck.check("919928088743", info("919928088743@c.us", null)).result()).isEqualTo(Result.MATCH);
        NumberCheck other = NumberCheck.check("919928088743", info("911111111111@c.us", null));
        assertThat(other.result()).isEqualTo(Result.MISMATCH);
        assertThat(other.reported()).isEqualTo("911111111111");
    }

    @Test
    void lidWidIsUnknown() {
        assertThat(NumberCheck.check("919928088743", info("1234567@lid", null)).result()).isEqualTo(Result.UNKNOWN);
    }

    @Test
    void objectFormIsRead() {
        ObjectNode i = mapper.createObjectNode();
        i.set("wid", mapper.createObjectNode().put("_serialized", "919928088743@c.us"));
        assertThat(NumberCheck.check("919928088743", i).result()).isEqualTo(Result.MATCH);
        ObjectNode parts = mapper.createObjectNode();
        parts.set("wid", mapper.createObjectNode().put("user", "919928088743").put("server", "c.us"));
        assertThat(NumberCheck.check("919928088743", parts).result()).isEqualTo(Result.MATCH);
    }

    @Test
    void missingInfoIsUnknown() {
        assertThat(NumberCheck.check("919928088743", null).result()).isEqualTo(Result.UNKNOWN);
        assertThat(NumberCheck.check("919928088743", mapper.createObjectNode()).result()).isEqualTo(Result.UNKNOWN);
    }
}
