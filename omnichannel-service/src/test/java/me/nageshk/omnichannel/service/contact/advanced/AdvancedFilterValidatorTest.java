package me.nageshk.omnichannel.service.contact.advanced;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;
import me.nageshk.omnichannel.common.api.ValidationException;
import me.nageshk.omnichannel.db.contact.advanced.Rule;
import me.nageshk.omnichannel.db.contact.advanced.RuleGroup;
import me.nageshk.omnichannel.db.contact.advanced.RuleNode;
import me.nageshk.omnichannel.db.contact.advanced.Uses;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class AdvancedFilterValidatorTest {

    private static final Resolved R = new Resolved(Set.of("a1"), Set.of("l1", "l2"), ZoneId.of("Asia/Kolkata"));

    private static RuleGroup and(RuleNode... nodes) {
        return new RuleGroup("and", false, Arrays.asList(nodes));
    }

    private static Rule rule(String field, String operator, Object value) {
        return new Rule(field, operator, value);
    }

    private static void assertFails(RuleGroup group, String key, String text) {
        assertThatThrownBy(() -> AdvancedFilterValidator.validate(group, R))
                .isInstanceOf(ValidationException.class)
                .satisfies(e -> assertThat(((ValidationException) e).fields()).isEqualTo(Map.of(key, text)));
    }

    private static RuleGroup nested(int levels) {
        RuleGroup inner = and(rule("name", "is", "A"));
        for (int i = 1; i < levels; i++) {
            inner = and(inner);
        }
        return inner;
    }

    @Test
    void controlCharactersAreRejected() {
        String single = "must be a text of 1 to 200 characters";
        String list = "must be a list of 1 to 20 values of 1 to 200 characters";
        for (String bad : List.of("\u0001", "\u0000", "a\u0000b")) {
            assertFails(and(rule("name", "contains", bad)), "rules[0].value", single);
        }
        assertFails(and(rule("labels", "hasAny", List.of("l1", "a\u0000b"))), "rules[0].value", list);
        assertFails(and(rule("source", "in", List.of("CHAT", "\u0001"))), "rules[0].value", list);
    }

    @Test
    void acceptsEveryOperatorOfEveryFieldType() {
        List<RuleNode> rules = new ArrayList<>();
        for (String f : List.of("name", "phone", "attributes.a1")) {
            for (String op : List.of("is", "isNot", "contains", "notContains", "startsWith", "endsWith")) {
                rules.add(rule(f, op, "12"));
            }
        }
        rules.add(rule("name", "isSet", null));
        rules.add(rule("name", "isNotSet", ""));
        for (String op : List.of("is", "isNot")) rules.add(rule("source", op, "CHAT"));
        for (String op : List.of("in", "notIn")) rules.add(rule("source", op, List.of("CHAT", "IMPORT", "MANUAL")));
        for (String f : List.of("numberHidden", "doNotContact")) rules.add(rule(f, "is", "true"));
        for (String f : List.of("creationTime", "lastMessageAt")) {
            rules.add(rule(f, "before", "2026-10-21"));
            rules.add(rule(f, "after", "2026-10-21"));
            rules.add(rule(f, "between", List.of("2026-10-01", "2026-10-21")));
            rules.add(rule(f, "isSet", null));
            rules.add(rule(f, "isNotSet", List.of()));
        }
        for (String op : List.of("has", "hasNot")) rules.add(rule("labels", op, "l1"));
        for (String op : List.of("hasAny", "hasAll", "hasNone")) rules.add(rule("labels", op, List.of("l1", "l2")));
        rules.add(rule("labels", "isEmpty", null));
        rules.add(rule("labels", "isNotEmpty", ""));
        assertThat(rules.size()).isLessThanOrEqualTo(50);
        assertThatCode(() -> AdvancedFilterValidator.validate(new RuleGroup("or", true, rules), R))
                .doesNotThrowAnyException();
    }

    @Test
    void returnsTheLabelsAndAttributesItUses() {
        Uses uses = AdvancedFilterValidator.validate(and(rule("labels", "has", "l2"),
                rule("labels", "hasAny", List.of("l1", "l2")), rule("attributes.a1", "is", "x")), R);
        assertThat(uses).isEqualTo(new Uses(List.of("a1"), List.of("l1", "l2")));
    }

    @Test
    void depthCapIsFiveLevels() {
        assertThatCode(() -> AdvancedFilterValidator.validate(nested(5), R)).doesNotThrowAnyException();
        assertFails(nested(6), "rules[0].rules[0].rules[0].rules[0].rules[0]", "is nested deeper than 5 levels");
    }

    @Test
    void aDeeplyNestedBodyStopsAtLevelSix() {
        assertFails(nested(1000), "rules[0].rules[0].rules[0].rules[0].rules[0]", "is nested deeper than 5 levels");
    }

    @Test
    void ruleCapIsFifty() {
        List<RuleNode> a = IntStream.range(0, 25).mapToObj(i -> (RuleNode) rule("name", "is", "A")).toList();
        List<RuleNode> b = IntStream.range(0, 25).mapToObj(i -> (RuleNode) rule("name", "is", "B")).toList();
        RuleGroup ok = and(new RuleGroup("or", false, a), new RuleGroup("or", false, b));
        assertThatCode(() -> AdvancedFilterValidator.validate(ok, R)).doesNotThrowAnyException();
        List<RuleNode> more = new ArrayList<>(b);
        more.add(rule("name", "is", "C"));
        assertFails(and(new RuleGroup("or", false, a), new RuleGroup("or", false, more)), "rules",
                "has more than 50 rules");
    }

    @Test
    void valueCaps() {
        Resolved many = new Resolved(Set.of(), IntStream.rangeClosed(1, 21).mapToObj(i -> "l" + i)
                .collect(java.util.stream.Collectors.toSet()), ZoneId.of("UTC"));
        List<String> ids20 = IntStream.rangeClosed(1, 20).mapToObj(i -> "l" + i).toList();
        List<String> ids21 = IntStream.rangeClosed(1, 21).mapToObj(i -> "l" + i).toList();
        assertThatCode(() -> AdvancedFilterValidator.validate(and(rule("name", "is", "x".repeat(200))), R))
                .doesNotThrowAnyException();
        assertFails(and(rule("name", "is", "x".repeat(201))), "rules[0].value",
                "must be a text of 1 to 200 characters");
        assertThatCode(() -> AdvancedFilterValidator.validate(and(rule("labels", "hasAny", ids20)), many))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> AdvancedFilterValidator.validate(and(rule("labels", "hasAny", ids21)), many))
                .isInstanceOf(ValidationException.class)
                .satisfies(e -> assertThat(((ValidationException) e).fields()).isEqualTo(Map.of("rules[0].value",
                        "must be a list of 1 to 20 values of 1 to 200 characters")));
    }

    @Test
    void emptyGroupsAndBadCombinators() {
        assertFails(new RuleGroup("and", false, List.of()), "rules", "must have at least one rule");
        assertFails(and(rule("name", "is", "A"), new RuleGroup("and", false, List.of())), "rules[1].rules",
                "must have at least one rule");
        assertFails(new RuleGroup("xor", false, List.of(rule("name", "is", "A"))), "rules",
                "combinator must be \"and\" or \"or\"");
        assertFails(and(rule("name", "is", "A"), new RuleGroup("AND", false, List.of(rule("name", "is", "A")))),
                "rules[1].combinator", "combinator must be \"and\" or \"or\"");
    }

    @Test
    void unknownFieldsOperatorsAndIds() {
        assertFails(and(rule("email", "is", "A")), "rules[0].field", "is not a filter field");
        assertFails(and(rule("attributes.zz", "is", "A")), "rules[0].field", "is not an attribute of this brand");
        assertFails(and(rule("source", "contains", "CHAT")), "rules[0].operator", "is not an operator of this field");
        assertFails(and(rule("labels", "has", "l9")), "rules[0].value", "is not a label of this brand");
        assertFails(and(rule("labels", "hasAny", List.of("l1", "l9"))), "rules[0].value",
                "is not a label of this brand");
        assertFails(and(new RuleGroup("or", false, java.util.Collections.singletonList(null))), "rules[0].rules[0]",
                "must be a rule or a group");
    }

    @Test
    void valueShapes() {
        assertFails(and(rule("source", "is", "WEB")), "rules[0].value", "must be one of CHAT, IMPORT, MANUAL");
        assertFails(and(rule("doNotContact", "is", "yes")), "rules[0].value", "must be \"true\" or \"false\"");
        assertFails(and(rule("creationTime", "before", "21-10-2026")), "rules[0].value",
                "must be a date YYYY-MM-DD");
        String two = "must be two dates YYYY-MM-DD, the first not after the second";
        assertFails(and(rule("creationTime", "between", List.of("2026-10-21", "2026-10-01"))), "rules[0].value", two);
        assertFails(and(rule("creationTime", "between", List.of("2026-10-01"))), "rules[0].value", two);
        assertFails(and(rule("name", "isSet", "x")), "rules[0].value", "takes no value");
        assertThatCode(() -> AdvancedFilterValidator.validate(
                and(rule("name", "isSet", ""), rule("name", "isSet", null), rule("name", "isSet", List.of())), R))
                .doesNotThrowAnyException();
    }

    @Test
    void phoneValueWithoutDigitsIsRejected() {
        assertFails(and(rule("phone", "contains", "+ abc")), "rules[0].value", "must contain digits");
        assertFails(and(rule("phone", "contains", "\u0663")), "rules[0].value", "must contain digits");
        assertFails(and(rule("phone", "contains", "\uFF13")), "rules[0].value", "must contain digits");
        assertThatCode(() -> AdvancedFilterValidator.validate(and(rule("phone", "contains", "+91 98")), R))
                .doesNotThrowAnyException();
    }

    @Test
    void rulesRoundTripThroughJackson() throws Exception {
        JsonMapper mapper = JsonMapper.builder().build();
        String json = "{\"combinator\":\"and\",\"not\":false,\"id\":\"g1\",\"rules\":[{\"id\":\"r1\",\"field\":\"name\","
                + "\"operator\":\"is\",\"value\":\"A\",\"valueSource\":\"value\"},{\"combinator\":\"or\",\"rules\":"
                + "[{\"field\":\"labels\",\"operator\":\"isEmpty\",\"value\":\"\"}]}]}";
        RuleGroup g = mapper.readValue(json, RuleGroup.class);
        assertThat(g.rules().get(0)).isEqualTo(new Rule("name", "is", "A"));
        RuleGroup inner = (RuleGroup) g.rules().get(1);
        assertThat(inner.not()).isFalse();
        assertThat(inner.rules().get(0)).isInstanceOf(Rule.class);
        String out = mapper.writeValueAsString(g);
        assertThat(out).doesNotContain("\"id\"").doesNotContain("@type").doesNotContain("\"type\"");
        assertThat(mapper.readValue(out, RuleGroup.class)).isEqualTo(g);
    }
}
