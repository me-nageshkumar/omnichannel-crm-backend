package me.nageshk.omnichannel.ai.turn;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import me.nageshk.omnichannel.ai.api.AiField;
import me.nageshk.omnichannel.ai.api.AiFieldType;
import me.nageshk.omnichannel.ai.api.StrictSchemas;
import me.nageshk.omnichannel.ai.provider.AiTool;
import org.junit.jupiter.api.Test;

class ProgressToolTest {

    private static final List<AiField> COLLECT = List.of(new AiField("name", "Their name", AiFieldType.TEXT),
            new AiField("city", null, AiFieldType.TEXT), new AiField("age", "", AiFieldType.NUMBER),
            new AiField("vip", "Paid member", AiFieldType.BOOLEAN));

    @Test
    void schemaIsStrictWithNullableKeys() {
        AiTool.FunctionTool t = ProgressTool.of(COLLECT).orElseThrow();

        Map<String, Object> props = new LinkedHashMap<>();
        props.put("name", Map.of("type", List.of("string", "null"), "description", "Their name"));
        props.put("city", Map.of("type", List.of("string", "null")));
        props.put("age", Map.of("type", List.of("number", "null")));
        props.put("vip", Map.of("type", List.of("boolean", "null"), "description", "Paid member"));
        Map<String, Object> golden = Map.of("type", "object", "properties", props,
                "required", List.of("name", "city", "age", "vip"), "additionalProperties", false);

        assertThat(t.name()).isEqualTo("note_progress").isEqualTo(ProgressTool.NAME);
        assertThat(t.parameters()).isEqualTo(golden);
        assertThat(StrictSchemas.ready(t.parameters())).isTrue();
        assertThat(t.description()).isEqualTo("Call this together with your reply whenever the customer has given one"
                + " of these details. Use null for anything not given yet. Never mention it to the customer.");
    }

    @Test
    void parseKeepsKnownNonNullKeys() {
        assertThat(ProgressTool.parse("{\"name\":\"Asha\",\"city\":null,\"x\":1}", COLLECT))
                .isEqualTo(Map.of("name", "Asha"));
        assertThat(ProgressTool.parse("{\"age\":31,\"vip\":false}", COLLECT)).isEqualTo(Map.of("age", 31, "vip", false));
        assertThat(ProgressTool.parse("not json", COLLECT)).as("a garbled call notes nothing").isEmpty();
        assertThat(ProgressTool.parse(null, COLLECT)).isEmpty();
        assertThat(ProgressTool.parse("{\"name\":\"Asha\"}", null)).isEmpty();
    }

    @Test
    void noCollectNoTool() {
        assertThat(ProgressTool.of(List.of())).isEmpty();
        assertThat(ProgressTool.of(null)).isEmpty();
    }
}
