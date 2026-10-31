package me.nageshk.omnichannel.crm.segment;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class SegmentRuleJsonTest {

    @Test
    void roundTripsANestedTree() {
        JsonMapper mapper = JsonMapper.builder().build();
        String json = """
                {"combinator":"and","rules":[
                  {"field":"contact.lifecycleStatus","operator":"is","value":"lead"},
                  {"combinator":"or","not":true,"rules":[
                    {"field":"channel.label","operator":"hasAny","value":["l1","l2"]},
                    {"field":"contact.email","operator":"isSet","value":null}]}]}""";
        SegmentRuleGroup g = mapper.readValue(json, SegmentRuleGroup.class);
        assertThat(g.combinator()).isEqualTo("and");
        assertThat(g.not()).as("absent not reads as false").isFalse();
        assertThat(g.rules()).hasSize(2);
        assertThat(g.rules().get(0)).isInstanceOf(SegmentRule.class);
        SegmentRuleGroup inner = (SegmentRuleGroup) g.rules().get(1);
        assertThat(inner.not()).isTrue();
        assertThat(((SegmentRule) inner.rules().get(0)).value()).isEqualTo(List.of("l1", "l2"));
        assertThat(((SegmentRule) inner.rules().get(1)).value()).isNull();
        SegmentRuleGroup again = mapper.readValue(mapper.writeValueAsString(g), SegmentRuleGroup.class);
        assertThat(again).isEqualTo(g);
    }
}
