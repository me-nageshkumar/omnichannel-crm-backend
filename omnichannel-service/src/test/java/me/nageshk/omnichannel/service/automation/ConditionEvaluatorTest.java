package me.nageshk.omnichannel.service.automation;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import me.nageshk.omnichannel.db.automation.ConditionNode;
import me.nageshk.omnichannel.db.automation.ConditionResult;
import me.nageshk.omnichannel.db.automation.EventData;
import org.junit.jupiter.api.Test;

class ConditionEvaluatorTest {

    private static ConditionNode leaf(String field, String op, Object value) {
        return new ConditionNode(null, null, null, field, op, value);
    }

    private static ConditionNode group(String combinator, ConditionNode... rules) {
        return new ConditionNode(combinator, null, List.of(rules), null, null, null);
    }

    private static boolean matches(ConditionNode n, Map<String, Object> v) {
        return ConditionEvaluator.matches(n, v);
    }

    private static Map<String, Object> vals(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    void noConditionsMatchEverything() {
        assertThat(matches(null, vals())).isTrue();
        assertThat(matches(group("and"), vals())).isTrue();
    }

    @Test
    void textIgnoresCaseAndTrims() {
        Map<String, Object> v = vals("message.text", " what is the price? ");
        assertThat(matches(leaf("message.text", "contains", "PRICE"), v)).isTrue();
        assertThat(matches(leaf("message.text", "starts_with", "what"), v)).isTrue();
        assertThat(matches(leaf("message.text", "ends_with", "PRICE?"), v)).isTrue();
        assertThat(matches(leaf("message.text", "is", "hello"), vals("message.text", "Hello "))).isTrue();
    }

    @Test
    void nullActualComparesAsEmpty() {
        Map<String, Object> v = vals();
        assertThat(matches(leaf("contact.name", "is_empty", null), v)).isTrue();
        assertThat(matches(leaf("contact.name", "contains", "a"), v)).isFalse();
        assertThat(matches(leaf("contact.name", "not_contains", "a"), v)).isTrue();
        assertThat(matches(leaf("contact.name", "is", "x"), v)).isFalse();
        assertThat(matches(leaf("contact.name", "is_not", "x"), v)).isTrue();
    }

    @Test
    void messageTypeIsOneOf() {
        ConditionNode n = leaf("message.type", "is_one_of", List.of("TEXT", "PHOTO"));
        assertThat(matches(n, vals("message.type", "PHOTO"))).isTrue();
        assertThat(matches(n, vals("message.type", "STICKER"))).isFalse();
    }

    @Test
    void anyGroupNestedInAll() {
        ConditionNode n = group("and", leaf("chat.new", "is", true),
                group("or", leaf("message.text", "contains", "hi"), leaf("message.type", "is_one_of", List.of("PHOTO"))));
        assertThat(matches(n, vals("chat.new", true, "message.text", "bye", "message.type", "PHOTO"))).isTrue();
        assertThat(matches(n, vals("chat.new", false, "message.text", "bye", "message.type", "PHOTO"))).isFalse();
    }

    @Test
    void notNegatesAGroup() {
        ConditionNode inner = group("and", leaf("chat.new", "is", true));
        ConditionNode negated = new ConditionNode("and", true, inner.rules(), null, null, null);
        assertThat(matches(negated, vals("chat.new", true))).isFalse();
        assertThat(matches(negated, vals("chat.new", false))).isTrue();
    }

    @Test
    void amountBetweenIsInclusive() {
        ConditionNode n = leaf("deal.amount", "between", List.of(1000, 5000));
        assertThat(matches(n, vals("deal.amount", new BigDecimal("1000")))).isTrue();
        assertThat(matches(n, vals("deal.amount", new BigDecimal("5000")))).isTrue();
        assertThat(matches(n, vals("deal.amount", new BigDecimal("5000.01")))).isFalse();
    }

    @Test
    void amountAtLeastAtMost() {
        ConditionNode atLeast = leaf("deal.amount", "at_least", 1000);
        assertThat(matches(atLeast, vals("deal.amount", new BigDecimal("1000")))).isTrue();
        assertThat(matches(atLeast, vals("deal.amount", new BigDecimal("999.99")))).isFalse();
        ConditionNode atMost = leaf("deal.amount", "at_most", 1000);
        assertThat(matches(atMost, vals("deal.amount", new BigDecimal("1000")))).isTrue();
        assertThat(matches(atMost, vals("deal.amount", new BigDecimal("1000.01")))).isFalse();
    }

    @Test
    void amountEmpty() {
        assertThat(matches(leaf("deal.amount", "is_empty", null), vals())).isTrue();
        assertThat(matches(leaf("deal.amount", "greater_than", 0), vals())).isFalse();
    }

    @Test
    void leavesAreDepthFirstWithPaths() {
        ConditionNode root = group("and", leaf("chat.new", "is", true),
                group("or", leaf("number", "is", "n1"), leaf("number", "is", "n2")));
        assertThat(ConditionEvaluator.leaves(root).stream().map(ConditionEvaluator.Leaf::path).toList())
                .containsExactly("rules[0]", "rules[1].rules[0]", "rules[1].rules[1]");
    }

    @Test
    void checkReportsActual() {
        ConditionNode n = leaf("chat.new", "is", true);
        ConditionResult r = ConditionEvaluator.check(new ConditionEvaluator.Leaf("rules[0]", n),
                vals("chat.new", false));
        assertThat(r.actual()).isEqualTo(false);
        assertThat(r.passed()).isFalse();
        assertThat(r.path()).isEqualTo("rules[0]");
        assertThat(r.field()).isEqualTo("chat.new");
        assertThat(r.operator()).isEqualTo("is");
        assertThat(r.value()).isEqualTo(true);
    }

    @Test
    void combineUsesStepResults() {
        ConditionNode root = group("and", leaf("chat.new", "is", true),
                group("or", leaf("number", "is", "n1"), leaf("number", "is", "n2")));
        assertThat(ConditionEvaluator.combine(root,
                Map.of("rules[0]", true, "rules[1].rules[0]", false, "rules[1].rules[1]", true))).isTrue();
        assertThat(ConditionEvaluator.combine(root,
                Map.of("rules[0]", true, "rules[1].rules[0]", false, "rules[1].rules[1]", false))).isFalse();
    }

    @Test
    void valuesReadEventData() {
        EventData d = new EventData(new EventData.ChatData("c1", true, null, null, null),
                new EventData.MessageData("m1", "hi", "TEXT", null), new EventData.NumberData("n1", "+91", "Main"), null,
                null, null, null, null);
        Map<String, Object> v = AutomationFields.values(d, true);
        assertThat(v).containsEntry("chat.new", true).containsEntry("message.text", "hi")
                .containsEntry("message.type", "TEXT").containsEntry("number", "n1")
                .containsEntry("contact.inCrm", true).doesNotContainKey("deal.amount");
    }

    @Test
    void kindsOfRealTypes() {
        assertThat(MessageKinds.kindOf("audio")).contains("AUDIO");
        assertThat(MessageKinds.kindOf("contacts")).contains("CONTACT_CARD");
        assertThat(MessageKinds.kindOf("poll_creation")).contains("POLL");
        for (String t : List.of("call_log", "revoked", "reaction", "ciphertext", "e2e_notification")) {
            assertThat(MessageKinds.kindOf(t)).isEmpty();
        }
    }

    private static EventData messageData(EventData.AdData ad) {
        return new EventData(null, new EventData.MessageData("m1", "hi", "TEXT", ad), null, null, null, null, null,
                null);
    }

    @Test
    void adConditionMatchesOnlyAdMessages() {
        Map<String, Object> ad = AutomationFields.values(
                messageData(new EventData.AdData("120", "https://fb.me/x", "Big sale")), false);
        Map<String, Object> plain = AutomationFields.values(messageData(null), false);
        assertThat(matches(leaf("message.ad", "is", true), ad)).isTrue();
        assertThat(matches(leaf("message.ad", "is", true), plain)).isFalse();
        assertThat(matches(leaf("message.ad", "is", false), plain)).isTrue();
    }

    @Test
    void missingHeadlineIsEmpty() {
        Map<String, Object> ad = AutomationFields.values(
                messageData(new EventData.AdData("120", null, "Big sale")), false);
        Map<String, Object> plain = AutomationFields.values(messageData(null), false);
        assertThat(matches(leaf("message.ad.headline", "is_empty", null), plain)).isTrue();
        assertThat(matches(leaf("message.ad.headline", "is_empty", null), ad)).isFalse();
        assertThat(matches(leaf("message.ad.headline", "contains", "sale"), ad)).isTrue();
        assertThat(matches(leaf("message.ad.headline", "contains", "sale"), plain)).isFalse();
        assertThat(matches(leaf("message.ad.sourceId", "is", "120"), ad)).isTrue();
    }
}
