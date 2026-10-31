package me.nageshk.omnichannel.crm.segment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.nageshk.omnichannel.common.api.CodedException;
import me.nageshk.omnichannel.crm.field.FieldType;
import me.nageshk.omnichannel.crm.segment.SegmentFields.Known;
import org.junit.jupiter.api.Test;

class SegmentValidatorTest {

    static final Known KNOWN = new Known(Set.of("tier"), Map.of("tier", FieldType.SELECT), Set.of(), Map.of(),
            Set.of("a1"));

    static SegmentRule rule(String field, String operator, Object value) {
        return new SegmentRule(field, operator, value);
    }

    static SegmentRuleGroup and(SegmentRuleNode... rules) {
        return new SegmentRuleGroup("and", false, List.of(rules));
    }

    static SegmentRuleGroup or(SegmentRuleNode... rules) {
        return new SegmentRuleGroup("or", false, List.of(rules));
    }

    static Map<String, String> errorsOf(SegmentRuleGroup g) {
        try {
            SegmentValidator.validate(g, KNOWN);
            throw new AssertionError("expected INVALID_SEGMENT");
        } catch (CodedException e) {
            assertThat(e.code()).isEqualTo("INVALID_SEGMENT");
            assertThat(e.status().value()).isEqualTo(400);
            return e.extra();
        }
    }

    @Test
    void acceptsTheThreeExampleAudiences() {
        // leads in Proposal for more than 7 days
        SegmentRuleGroup proposal = and(rule("contact.lifecycleStatus", "is", "lead"),
                and(rule("deal.pipelineId", "is", "p1"), rule("deal.stageId", "is", "s4"),
                        rule("deal.daysInStage", "gt", "7")));
        // customers in Mumbai with an open deal over 50,000
        SegmentRuleGroup mumbai = and(rule("contact.lifecycleStatus", "is", "customer"),
                rule("contact.custom:tier", "in", List.of("gold", "silver")),
                and(rule("deal.status", "is", "OPEN"), rule("deal.amount", "gt", "50000")));
        // gold-tier contacts nobody has messaged in 30 days
        SegmentRuleGroup quiet = and(rule("contact.custom:tier", "is", "gold"),
                or(rule("channel.lastMessageAt", "moreThanDaysAgo", "30"), rule("channel.lastMessageAt", "isNotSet", null)),
                rule("channel.label", "hasAny", List.of("l1", "l2")), rule("channel.attribute:a1", "contains", "vip"),
                rule("channel.doNotContact", "is", "false"), rule("contact.creationTime", "between", List.of("2026-01-01", "2026-06-30")));
        for (SegmentRuleGroup g : List.of(proposal, mumbai, quiet)) {
            assertThatCode(() -> SegmentValidator.validate(g, KNOWN)).doesNotThrowAnyException();
        }
    }

    @Test
    void collectsEveryError() {
        SegmentRuleGroup g = and(rule("contact.phone", "is", "x"), rule("contact.openDealCount", "contains", "3"),
                and(rule("contact.email", "is", "")));
        Map<String, String> errors = errorsOf(g);
        assertThat(errors).containsKeys("rules[0].field", "rules[1].operator", "rules[2].rules[0].value");
        assertThat(errors.get("rules[0].field")).isEqualTo("is not a field segments can use");
    }

    @Test
    void refusesStageWithoutPipelineInSameAndGroup() {
        assertThat(errorsOf(and(rule("deal.stageId", "is", "s1")))).containsEntry("rules[0]",
                "needs a pipeline rule in the same group");
        // the pipeline rule in the parent does not count
        assertThat(errorsOf(and(rule("deal.pipelineId", "is", "p1"), and(rule("deal.stageId", "is", "s1")))))
                .containsKey("rules[1].rules[0]");
        // nor does an OR group
        assertThat(errorsOf(or(rule("deal.pipelineId", "is", "p1"), rule("deal.stageId", "is", "s1"))))
                .containsKey("rules[1]");
    }

    @Test
    void acceptsStageWithPipelineInSameAndGroup() {
        assertThatCode(() -> SegmentValidator.validate(
                and(rule("deal.pipelineId", "is", "p1"), rule("deal.stageId", "in", List.of("s1", "s2"))), KNOWN))
                .doesNotThrowAnyException();
    }

    @Test
    void refusesDepthOverThree() {
        SegmentRuleGroup four = and(and(and(and(rule("contact.email", "isSet", null)))));
        assertThat(errorsOf(four)).containsKey("rules[0].rules[0].rules[0]");
        SegmentRuleGroup three = and(and(and(rule("contact.email", "isSet", null))));
        assertThatCode(() -> SegmentValidator.validate(three, KNOWN)).doesNotThrowAnyException();
    }

    @Test
    void refusesOver30Rules() {
        List<SegmentRuleNode> many = new ArrayList<>();
        for (int i = 0; i < 31; i++) many.add(rule("contact.email", "isSet", null));
        assertThat(errorsOf(new SegmentRuleGroup("and", false, many))).containsKey("rules");
        assertThatCode(() -> SegmentValidator.validate(new SegmentRuleGroup("and", false, many.subList(0, 30)), KNOWN))
                .doesNotThrowAnyException();
    }

    @Test
    void refusesBadDateAndDays() {
        Map<String, String> errors = errorsOf(and(rule("contact.creationTime", "before", "2026-13-01"),
                rule("contact.creationTime", "withinLastDays", "0"),
                rule("contact.creationTime", "moreThanDaysAgo", "3651"),
                rule("contact.creationTime", "after", "yesterday")));
        assertThat(errors).containsKeys("rules[0].value", "rules[1].value", "rules[2].value", "rules[3].value");
        assertThatCode(() -> SegmentValidator.validate(and(rule("contact.creationTime", "withinLastDays", "3650"),
                rule("contact.creationTime", "moreThanDaysAgo", "1")), KNOWN)).doesNotThrowAnyException();
    }

    @Test
    void betweenNeedsTwoOrderedValues() {
        assertThat(errorsOf(and(rule("deal.amount", "between", List.of("10"))))).containsKey("rules[0].value");
        assertThat(errorsOf(and(rule("deal.amount", "between", List.of("10", "5"))))).containsKey("rules[0].value");
        assertThat(errorsOf(and(rule("contact.creationTime", "between", List.of("2026-02-01", "2026-01-01")))))
                .containsKey("rules[0].value");
        assertThatCode(() -> SegmentValidator.validate(and(rule("deal.amount", "between", List.of("5", "10.50"))), KNOWN))
                .doesNotThrowAnyException();
    }

    @Test
    void unknownCustomKeyIsNotAShapeError() {
        assertThatCode(() -> SegmentValidator.validate(and(rule("contact.custom:gone", "is", "x"),
                rule("channel.attribute:a9", "contains", "y"), rule("deal.custom:old", "gt", "1")), KNOWN))
                .doesNotThrowAnyException();
    }

    @Test
    void refusesEmptyGroupsBadCombinatorsAndValueShapes() {
        Map<String, String> errors = errorsOf(new SegmentRuleGroup("xor", false, List.of(
                and(), rule("contact.lifecycleStatus", "in", "lead"), rule("deal.rotting", "is", "maybe"),
                rule("contact.openDealCount", "gt", "many"), rule("contact.email", "isSet", "x"),
                rule("channel.label", "hasAny", List.of()))));
        assertThat(errors).containsKeys("combinator", "rules[0]", "rules[1].value", "rules[2].value", "rules[3].value",
                "rules[4].value", "rules[5].value");
        List<String> tooMany = new ArrayList<>();
        for (int i = 0; i < 51; i++) tooMany.add("v" + i);
        assertThat(errorsOf(and(rule("contact.lifecycleStatus", "in", tooMany)))).containsKey("rules[0].value");
        assertThat(errorsOf(and(rule("contact.email", "is", "x".repeat(201))))).containsKey("rules[0].value");
    }
}
