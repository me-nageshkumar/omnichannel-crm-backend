package me.nageshk.omnichannel.contact.advanced;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.nageshk.omnichannel.api.ValidationException;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class AdvancedFilterCompilerTest {

    private static final Resolved R = new Resolved(Set.of("a1"), Set.of("l1", "l2"), ZoneId.of("Asia/Kolkata"));
    private static final Resolved UTC = new Resolved(Set.of("a1"), Set.of("l1", "l2"), ZoneId.of("UTC"));
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static RuleGroup and(RuleNode... nodes) {
        return new RuleGroup("and", false, Arrays.asList(nodes));
    }

    private static RuleGroup or(RuleNode... nodes) {
        return new RuleGroup("or", false, Arrays.asList(nodes));
    }

    private static Rule rule(String field, String operator, Object value) {
        return new Rule(field, operator, value);
    }

    private static void assertCompiles(RuleGroup g, String json) {
        assertThat(JSON.readTree(AdvancedFilterCompiler.compile(g, R).toJson())).isEqualTo(JSON.readTree(json));
    }

    private static Date date(String instant) {
        return Date.from(Instant.parse(instant));
    }

    private static Document only(Resolved resolved, Rule r) {
        Document d = AdvancedFilterCompiler.compile(and(r), resolved);
        return (Document) d.getList("$and", Document.class).get(0);
    }

    @Test
    void textOperators() {
        assertCompiles(and(rule("name", "is", "Asha")),
                """
                {"$and":[{"name":{"$regex":"^Asha$","$options":"i"}}]}""");
        assertCompiles(and(rule("name", "isNot", "Asha")),
                """
                {"$and":[{"$nor":[{"name":{"$regex":"^Asha$","$options":"i"}}]}]}""");
        assertCompiles(and(rule("name", "contains", "sha")),
                """
                {"$and":[{"name":{"$regex":"sha","$options":"i"}}]}""");
        assertCompiles(and(rule("name", "notContains", "sha")),
                """
                {"$and":[{"$nor":[{"name":{"$regex":"sha","$options":"i"}}]}]}""");
        assertCompiles(and(rule("name", "startsWith", "Ash")),
                """
                {"$and":[{"name":{"$regex":"^Ash","$options":"i"}}]}""");
        assertCompiles(and(rule("name", "endsWith", "sha")),
                """
                {"$and":[{"name":{"$regex":"sha$","$options":"i"}}]}""");
        assertCompiles(and(rule("name", "isSet", null)),
                """
                {"$and":[{"name":{"$exists":true,"$nin":[null,""]}}]}""");
        assertCompiles(and(rule("name", "isNotSet", null)),
                """
                {"$and":[{"$or":[{"name":{"$exists":false}},{"name":null},{"name":""}]}]}""");
        assertCompiles(and(rule("name", "is", " Asha ")),
                """
                {"$and":[{"name":{"$regex":"^Asha$","$options":"i"}}]}""");
    }

    @Test
    void attributeAndPhoneRules() {
        assertCompiles(and(rule("attributes.a1", "is", "Pune")),
                """
                {"$and":[{"attributes.a1":{"$regex":"^Pune$","$options":"i"}}]}""");
        assertCompiles(and(rule("phone", "contains", "+91 98-12")),
                """
                {"$and":[{"phone":{"$regex":"919812","$options":"i"}}]}""");
        assertCompiles(and(rule("phone", "startsWith", "+91 98")),
                """
                {"$and":[{"phone":{"$regex":"^9198","$options":"i"}}]}""");
    }

    @Test
    void regexMetacharactersAreEscaped() {
        assertCompiles(and(rule("name", "contains", ".*+?()[]{}|^$\\-/")),
                """
                {"$and":[{"name":{"$regex":"\\\\.\\\\*\\\\+\\\\?\\\\(\\\\)\\\\[\\\\]\\\\{\\\\}\\\\|\\\\^\\\\$\\\\\\\\\\\\-\\\\/","$options":"i"}}]}""");
    }

    @Test
    void hostileValuesStayLiterals() {
        assertCompiles(and(rule("name", "contains", "$where")), """
                {"$and":[{"name":{"$regex":"\\\\$where","$options":"i"}}]}""");
        assertCompiles(and(rule("name", "is", "{$ne:1}")), """
                {"$and":[{"name":{"$regex":"^\\\\{\\\\$ne:1\\\\}$","$options":"i"}}]}""");
        assertThatThrownBy(() -> AdvancedFilterCompiler.compile(and(rule("labels", "has", "$ne")), R))
                .isInstanceOf(ValidationException.class)
                .satisfies(e -> assertThat(((ValidationException) e).fields())
                        .isEqualTo(Map.of("rules[0].value", "is not a label of this brand")));
    }

    @Test
    void enumAndBooleanOperators() {
        assertCompiles(and(rule("source", "is", "CHAT")), """
                {"$and":[{"source":{"$eq":"CHAT"}}]}""");
        assertCompiles(and(rule("source", "isNot", "CHAT")), """
                {"$and":[{"source":{"$ne":"CHAT"}}]}""");
        assertCompiles(and(rule("source", "in", List.of("CHAT", "IMPORT"))), """
                {"$and":[{"source":{"$in":["CHAT","IMPORT"]}}]}""");
        assertCompiles(and(rule("source", "notIn", List.of("CHAT", "IMPORT"))), """
                {"$and":[{"source":{"$nin":["CHAT","IMPORT"]}}]}""");
        assertCompiles(and(rule("doNotContact", "is", "true")), """
                {"$and":[{"doNotContact":{"$ne":null}}]}""");
        assertCompiles(and(rule("doNotContact", "is", "false")), """
                {"$and":[{"doNotContact":null}]}""");
        assertCompiles(and(rule("numberHidden", "is", "true")), """
                {"$and":[{"phone":null}]}""");
        assertCompiles(and(rule("numberHidden", "is", "false")), """
                {"$and":[{"phone":{"$ne":null}}]}""");
    }

    @Test
    void dateBoundariesInTheZone() {
        assertThat(only(R, rule("creationTime", "before", "2026-10-21")))
                .isEqualTo(new Document("creationTime", new Document("$lt", date("2026-10-20T18:30:00Z"))));
        assertThat(only(R, rule("creationTime", "after", "2026-10-21")))
                .isEqualTo(new Document("creationTime", new Document("$gte", date("2026-10-21T18:30:00Z"))));
        assertThat(only(R, rule("creationTime", "between", List.of("2026-10-01", "2026-10-21"))))
                .isEqualTo(new Document("creationTime", new Document("$gte", date("2026-09-30T18:30:00Z"))
                        .append("$lt", date("2026-10-21T18:30:00Z"))));
        assertThat(only(UTC, rule("creationTime", "before", "2026-10-21")))
                .isEqualTo(new Document("creationTime", new Document("$lt", date("2026-10-21T00:00:00Z"))));
        assertThat(only(R, rule("lastMessageAt", "isSet", null)))
                .isEqualTo(new Document("lastMessageAt", new Document("$ne", null)));
        assertThat(only(R, rule("lastMessageAt", "isNotSet", null)))
                .isEqualTo(new Document("lastMessageAt", null));
    }

    @Test
    void labelOperators() {
        assertCompiles(and(rule("labels", "has", "l1")), """
                {"$and":[{"labels":"l1"}]}""");
        assertCompiles(and(rule("labels", "hasNot", "l1")), """
                {"$and":[{"labels":{"$ne":"l1"}}]}""");
        assertCompiles(and(rule("labels", "hasAny", List.of("l1", "l2"))), """
                {"$and":[{"labels":{"$in":["l1","l2"]}}]}""");
        assertCompiles(and(rule("labels", "hasAll", List.of("l1", "l2"))), """
                {"$and":[{"$and":[{"labels":"l1"},{"labels":"l2"}]}]}""");
        assertCompiles(and(rule("labels", "hasNone", List.of("l1", "l2"))), """
                {"$and":[{"labels":{"$nin":["l1","l2"]}}]}""");
        assertCompiles(and(rule("labels", "isEmpty", null)), """
                {"$and":[{"$or":[{"labels":{"$exists":false}},{"labels":{"$size":0}}]}]}""");
        assertCompiles(and(rule("labels", "isNotEmpty", null)), """
                {"$and":[{"labels.0":{"$exists":true}}]}""");
    }

    @Test
    void groupsAndNot() {
        assertCompiles(or(rule("name", "is", "A"), rule("labels", "has", "l1")), """
                {"$or":[{"name":{"$regex":"^A$","$options":"i"}},{"labels":"l1"}]}""");
        assertCompiles(new RuleGroup("and", true, List.of(rule("labels", "has", "l1"), rule("labels", "has", "l2"))),
                """
                {"$nor":[{"$and":[{"labels":"l1"},{"labels":"l2"}]}]}""");
        assertCompiles(or(rule("labels", "has", "l1"), and(rule("labels", "has", "l2"))), """
                {"$or":[{"labels":"l1"},{"$and":[{"labels":"l2"}]}]}""");
    }

    @Test
    void onlyAllowedOperatorsAppear() {
        List<RuleNode> nodes = new ArrayList<>();
        for (String op : List.of("is", "isNot", "contains", "notContains", "startsWith", "endsWith", "isSet",
                "isNotSet")) {
            boolean noValue = op.equals("isSet") || op.equals("isNotSet");
            nodes.add(rule("name", op, noValue ? null : "x"));
            nodes.add(rule("phone", op, noValue ? null : "1"));
            nodes.add(rule("attributes.a1", op, noValue ? null : "x"));
        }
        for (String op : List.of("is", "isNot")) {
            nodes.add(rule("source", op, "CHAT"));
        }
        for (String op : List.of("in", "notIn")) {
            nodes.add(rule("source", op, List.of("CHAT")));
        }
        for (String f : List.of("doNotContact", "numberHidden")) {
            nodes.add(rule(f, "is", "true"));
            nodes.add(rule(f, "is", "false"));
        }
        for (String f : List.of("creationTime", "lastMessageAt")) {
            nodes.add(rule(f, "before", "2026-10-21"));
            nodes.add(rule(f, "after", "2026-10-21"));
            nodes.add(rule(f, "between", List.of("2026-10-01", "2026-10-21")));
            nodes.add(rule(f, "isSet", null));
            nodes.add(rule(f, "isNotSet", null));
        }
        nodes.add(rule("labels", "has", "l1"));
        nodes.add(rule("labels", "hasNot", "l1"));
        nodes.add(rule("labels", "hasAny", List.of("l1")));
        nodes.add(rule("labels", "hasAll", List.of("l1", "l2")));
        nodes.add(rule("labels", "hasNone", List.of("l1")));
        nodes.add(rule("labels", "isEmpty", null));
        nodes.add(rule("labels", "isNotEmpty", null));
        RuleGroup tree = new RuleGroup("or", true, List.of(new RuleGroup("and", true, nodes), or(nodes.get(0))));
        Set<String> found = new java.util.TreeSet<>();
        collect(AdvancedFilterCompiler.compile(tree, R), found);
        assertThat(Set.of("$and", "$or", "$nor", "$eq", "$ne", "$in", "$nin", "$regex", "$options", "$exists",
                "$gte", "$lt", "$size")).containsAll(found);
        assertThat(found).contains("$and", "$or", "$nor", "$eq", "$ne", "$in", "$nin", "$regex", "$options",
                "$exists", "$gte", "$lt", "$size");
    }

    private static void collect(Object o, Set<String> keys) {
        if (o instanceof Map<?, ?> m) {
            m.forEach((k, v) -> {
                if (k.toString().startsWith("$")) {
                    keys.add(k.toString());
                }
                collect(v, keys);
            });
        } else if (o instanceof Collection<?> c) {
            c.forEach(v -> collect(v, keys));
        }
    }

    @Test
    void invalidTreeIsRejected() {
        assertThatThrownBy(() -> AdvancedFilterCompiler.compile(and(rule("email", "is", "x")), R))
                .isInstanceOf(ValidationException.class)
                .satisfies(e -> assertThat(((ValidationException) e).fields()).containsKey("rules[0].field"));
    }
}
