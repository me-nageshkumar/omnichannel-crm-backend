package me.nageshk.omnichannel.service.contact.advanced;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import me.nageshk.omnichannel.common.api.ValidationException;
import me.nageshk.omnichannel.db.contact.ContactSource;
import me.nageshk.omnichannel.db.contact.advanced.Rule;
import me.nageshk.omnichannel.db.contact.advanced.RuleGroup;
import me.nageshk.omnichannel.db.contact.advanced.Uses;

/** Checks a rule tree against the field table, the caps and the workspace's ids; the first failure wins. */
public final class AdvancedFilterValidator {

    public static final int MAX_DEPTH = 5;
    public static final int MAX_RULES = 50;
    public static final int MAX_VALUES = 20;
    public static final int MAX_VALUE_LENGTH = 200;

    private static final String SINGLE = "must be a text of 1 to 200 characters";
    private static final String LIST = "must be a list of 1 to 20 values of 1 to 200 characters";
    private static final String SOURCES = "must be one of CHAT, IMPORT, MANUAL";
    private static final String DATE = "must be a date YYYY-MM-DD";
    private static final String DATES = "must be two dates YYYY-MM-DD, the first not after the second";

    private static final Set<String> TEXT_SINGLE = Set.of("is", "isNot", "contains", "notContains", "startsWith",
            "endsWith");
    private static final Set<String> LABEL_SINGLE = Set.of("has", "hasNot");
    private static final Set<String> LABEL_LIST = Set.of("hasAny", "hasAll", "hasNone");

    private final Resolved resolved;
    private final Set<String> attributeIds = new TreeSet<>();
    private final Set<String> labelIds = new TreeSet<>();
    private int ruleCount;

    private AdvancedFilterValidator(Resolved resolved) {
        this.resolved = resolved;
    }

    public static Uses validate(RuleGroup rules, Resolved resolved) {
        AdvancedFilterValidator v = new AdvancedFilterValidator(resolved);
        v.group(rules, "", 1);
        return new Uses(List.copyOf(v.attributeIds), List.copyOf(v.labelIds));
    }

    private void group(RuleGroup g, String path, int level) {
        if (level > MAX_DEPTH) {
            throw fail(path, "is nested deeper than " + MAX_DEPTH + " levels");
        }
        boolean root = path.isEmpty();
        if (!"and".equals(g.combinator()) && !"or".equals(g.combinator())) {
            throw fail(root ? "rules" : path + ".combinator", "combinator must be \"and\" or \"or\"");
        }
        if (g.rules() == null || g.rules().isEmpty()) {
            throw fail(root ? "rules" : path + ".rules", "must have at least one rule");
        }
        for (int i = 0; i < g.rules().size(); i++) {
            String childPath = (root ? "" : path + ".") + "rules[" + i + "]";
            switch (g.rules().get(i)) {
                case null -> throw fail(childPath, "must be a rule or a group");
                case RuleGroup child -> group(child, childPath, level + 1);
                case Rule child -> rule(child, childPath);
            }
        }
    }

    private void rule(Rule r, String path) {
        if (++ruleCount > MAX_RULES) {
            throw fail("rules", "has more than " + MAX_RULES + " rules");
        }
        FilterFields.Type type = FilterFields.typeOf(r.field())
                .orElseThrow(() -> fail(path + ".field", "is not a filter field"));
        if (r.field().startsWith(FilterFields.ATTRIBUTE_PREFIX)) {
            String id = r.field().substring(FilterFields.ATTRIBUTE_PREFIX.length());
            if (!resolved.attributeIds().contains(id)) {
                throw fail(path + ".field", "is not an attribute of this brand");
            }
            attributeIds.add(id);
        }
        String op = r.operator();
        if (op == null || !FilterFields.operators(type).contains(op)) {
            throw fail(path + ".operator", "is not an operator of this field");
        }
        String vp = path + ".value";
        Object value = r.value();
        if (FilterFields.NO_VALUE.contains(op)) {
            if (!(value == null || "".equals(value) || (value instanceof List<?> l && l.isEmpty()))) {
                throw fail(vp, "takes no value");
            }
            return;
        }
        switch (type) {
            case TEXT -> {
                String s = single(value, vp);
                if ("phone".equals(r.field()) && s.chars().noneMatch(c -> c >= '0' && c <= '9')) {
                    throw fail(vp, "must contain digits");
                }
            }
            case ENUM -> {
                if (List.of("in", "notIn").contains(op)) {
                    list(value, vp).forEach(s -> source(s, vp));
                } else {
                    source(single(value, vp), vp);
                }
            }
            case BOOLEAN -> {
                String s = single(value, vp);
                if (!s.equals("true") && !s.equals("false")) {
                    throw fail(vp, "must be \"true\" or \"false\"");
                }
            }
            case DATE -> {
                if ("between".equals(op)) {
                    between(value, vp);
                } else if (date(value) == null) {
                    throw fail(vp, DATE);
                }
            }
            case LABELS -> {
                List<String> ids = LABEL_SINGLE.contains(op) ? List.of(single(value, vp))
                        : list(value, vp);
                for (String id : ids) {
                    if (!resolved.labelIds().contains(id)) {
                        throw fail(vp, "is not a label of this brand");
                    }
                    labelIds.add(id);
                }
            }
        }
    }

    private String single(Object value, String vp) {
        if (!validText(value)) {
            throw fail(vp, SINGLE);
        }
        return (String) value;
    }

    private List<String> list(Object value, String vp) {
        if (!(value instanceof List<?> l) || l.isEmpty() || l.size() > MAX_VALUES
                || !l.stream().allMatch(AdvancedFilterValidator::validText)) {
            throw fail(vp, LIST);
        }
        return l.stream().map(String.class::cast).toList();
    }

    private static boolean validText(Object o) {
        return o instanceof String s && !s.isBlank() && s.length() <= MAX_VALUE_LENGTH
                && s.chars().noneMatch(Character::isISOControl);
    }

    private void source(String s, String vp) {
        try {
            ContactSource.valueOf(s);
        } catch (IllegalArgumentException e) {
            throw fail(vp, SOURCES);
        }
    }

    private static LocalDate date(Object o) {
        if (!(o instanceof String s)) {
            return null;
        }
        try {
            return LocalDate.parse(s);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private void between(Object value, String vp) {
        if (!(value instanceof List<?> l) || l.size() != 2) {
            throw fail(vp, DATES);
        }
        LocalDate from = date(l.get(0));
        LocalDate to = date(l.get(1));
        if (from == null || to == null || from.isAfter(to)) {
            throw fail(vp, DATES);
        }
    }

    private static ValidationException fail(String path, String text) {
        return new ValidationException(path, text);
    }
}
