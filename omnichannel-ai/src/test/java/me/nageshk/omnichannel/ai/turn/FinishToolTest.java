package me.nageshk.omnichannel.ai.turn;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import me.nageshk.omnichannel.ai.api.AiField;
import me.nageshk.omnichannel.ai.api.AiFieldType;
import me.nageshk.omnichannel.ai.api.AiOutcome;
import me.nageshk.omnichannel.ai.provider.AiTool;
import org.junit.jupiter.api.Test;

class FinishToolTest {

    @Test
    void schemaListsOutcomesAndFields() {
        AiTool.FunctionTool t = FinishTool.of(
                List.of(new AiOutcome("booked", "Booked", "They picked a slot"), new AiOutcome("later", "Later", null)),
                List.of(new AiField("city", "Where they live", AiFieldType.TEXT), new AiField("age", null, AiFieldType.NUMBER),
                        new AiField("vip", "", AiFieldType.BOOLEAN))).orElseThrow();

        Map<String, Object> fieldProps = new LinkedHashMap<>();
        fieldProps.put("city", Map.of("type", List.of("string", "null"), "description", "Where they live"));
        fieldProps.put("age", Map.of("type", List.of("number", "null")));
        fieldProps.put("vip", Map.of("type", List.of("boolean", "null")));
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("outcome", Map.of("type", "string", "enum", List.of("booked", "later")));
        props.put("fields", Map.of("type", "object", "properties", fieldProps, "required", List.of("city", "age", "vip"),
                "additionalProperties", false));
        props.put("reply", Map.of("type", List.of("string", "null"), "description",
                "The last message the customer gets: a closing message, never a question. Null for none."));
        Map<String, Object> golden = Map.of("type", "object", "properties", props,
                "required", List.of("outcome", "fields", "reply"), "additionalProperties", false);

        assertThat(t.name()).isEqualTo("finish").isEqualTo(FinishTool.NAME);
        assertThat(t.parameters()).isEqualTo(golden);
        assertThat(t.description()).isEqualTo("""
                Ends the conversation with an outcome. Call it only when the conversation is over: never while you \
                still need an answer or a confirmation from the customer (then reply normally and wait for it). \
                The outcomes:
                - booked (Booked): They picked a slot
                - later (Later)
                Put the details you learned in fields (null when unknown). reply is the last message the customer \
                gets: a closing message, never a question (null for none).""");
    }

    @Test
    void noOutcomesNoTool() {
        assertThat(FinishTool.of(List.of(), List.of(new AiField("city", null, AiFieldType.TEXT)))).isEmpty();
        assertThat(FinishTool.of(null, null)).isEmpty();
        assertThat(FinishTool.of(List.of(new AiOutcome("a", "A", null)), null).orElseThrow().parameters())
                .extracting(p -> ((Map<?, ?>) ((Map<?, ?>) p.get("properties")).get("fields")).get("required"))
                .isEqualTo(List.of());
    }
}
