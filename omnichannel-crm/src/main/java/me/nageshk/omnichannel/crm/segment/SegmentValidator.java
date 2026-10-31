package me.nageshk.omnichannel.crm.segment;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.nageshk.omnichannel.crm.segment.SegmentFields.FieldSpec;
import me.nageshk.omnichannel.crm.segment.SegmentFields.Known;

/**
 * Checks the shape of a rule tree (spec D C3, C4, C22) and reports every problem at once, keyed by path. A rule whose
 * custom field or attribute no longer exists is not a shape problem: it opens as {@code missing} and matches nothing.
 */
public final class SegmentValidator {

    private static final Set<String> COMBINATORS = Set.of("and", "or");

    private SegmentValidator() {}

    /** @throws me.nageshk.omnichannel.common.api.CodedException 400 {@code INVALID_SEGMENT} with every problem */
    public static void validate(SegmentRuleGroup rules, Known known) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (rules == null) {
            errors.put("rules", "is required");
        } else {
            int[] count = {0};
            group(rules, "", 1, known, errors, count);
            if (count[0] > SegmentFields.MAX_RULES) {
                errors.put("rules", "has more than " + SegmentFields.MAX_RULES + " rules");
            }
        }
        if (!errors.isEmpty()) throw SegmentErrors.invalid(errors);
    }

    private static void group(SegmentRuleGroup g, String path, int level, Known known, Map<String, String> errors,
            int[] count) {
        String self = path.isEmpty() ? "rules" : path;
        if (g.combinator() == null || !COMBINATORS.contains(g.combinator())) {
            errors.put(path.isEmpty() ? "combinator" : path + ".combinator", "must be and or or");
        }
        if (level > SegmentFields.MAX_DEPTH) {
            errors.put(self, "is nested deeper than " + SegmentFields.MAX_DEPTH + " levels");
            return;
        }
        if (g.rules().isEmpty()) {
            errors.put(self, "needs at least one rule");
            return;
        }
        boolean and = "and".equals(g.combinator());
        boolean hasPipeline = and && g.rules().stream().anyMatch(n -> n instanceof SegmentRule r
                && "deal.pipelineId".equals(r.field()));
        for (int i = 0; i < g.rules().size(); i++) {
            String at = (path.isEmpty() ? "rules" : path + ".rules") + "[" + i + "]";
            SegmentRuleNode n = g.rules().get(i);
            if (n instanceof SegmentRuleGroup child) {
                group(child, at, level + 1, known, errors, count);
            } else if (n instanceof SegmentRule r) {
                count[0]++;
                rule(r, at, known, errors);
                if ("deal.stageId".equals(r.field()) && !hasPipeline) {
                    errors.put(at, "needs a pipeline rule in the same group");
                }
            }
        }
    }

    private static void rule(SegmentRule r, String at, Known known, Map<String, String> errors) {
        FieldSpec spec = SegmentFields.spec(r.field(), known).orElse(null);
        if (spec == null) {
            if (!SegmentFields.isCustomShaped(r.field())) errors.put(at + ".field", "is not a field segments can use");
            return; // a missing custom field or attribute: shape unknown, matches nothing
        }
        if (r.operator() == null || !SegmentFields.operators(spec.type()).contains(r.operator())) {
            errors.put(at + ".operator", "is not an operator of a " + spec.type().name().toLowerCase() + " field");
            return;
        }
        String problem = value(spec.type(), r.operator(), r.value());
        if (problem != null) errors.put(at + ".value", problem);
    }

    /** The message for a value of the wrong shape, or null. */
    static String value(SegmentValueType type, String op, Object value) {
        if (SegmentFields.NO_VALUE.contains(op)) return value == null ? null : "takes no value";
        if (SegmentFields.DAYS_VALUE.contains(op)) {
            Integer n = value instanceof String s ? parseInt(s) : null;
            return n == null || n < 1 || n > SegmentFields.MAX_DAYS
                    ? "must be a number of days from 1 to " + SegmentFields.MAX_DAYS : null;
        }
        if (SegmentFields.LIST_VALUE.contains(op)) {
            if (!(value instanceof List<?> l) || l.isEmpty() || l.size() > SegmentFields.MAX_VALUES
                    || !l.stream().allMatch(SegmentValidator::text)) {
                return "must be a list of 1 to " + SegmentFields.MAX_VALUES + " values of 1 to "
                        + SegmentFields.MAX_VALUE_LENGTH + " characters";
            }
            if ("between".equals(op)) {
                if (l.size() != 2) return "needs two values";
                String a = (String) l.get(0), b = (String) l.get(1);
                return switch (type) {
                    case NUMBER, MONEY -> {
                        BigDecimal x = parseNumber(a), y = parseNumber(b);
                        yield x == null || y == null ? "must be two numbers"
                                : x.compareTo(y) > 0 ? "must be two numbers, the first not greater than the second" : null;
                    }
                    case DATE -> {
                        LocalDate x = parseDate(a), y = parseDate(b);
                        yield x == null || y == null ? "must be two dates YYYY-MM-DD"
                                : x.isAfter(y) ? "must be two dates YYYY-MM-DD, the first not after the second" : null;
                    }
                    default -> "does not take between";
                };
            }
            return null;
        }
        if (!text(value)) return "must be a text of 1 to " + SegmentFields.MAX_VALUE_LENGTH + " characters";
        String s = (String) value;
        return switch (type) {
            case NUMBER, MONEY -> parseNumber(s) == null ? "must be a number" : null;
            case DATE -> parseDate(s) == null ? "must be a date YYYY-MM-DD" : null;
            case BOOLEAN -> "true".equals(s) || "false".equals(s) ? null : "must be true or false";
            default -> null;
        };
    }

    private static boolean text(Object o) {
        return o instanceof String s && !s.isBlank() && s.length() <= SegmentFields.MAX_VALUE_LENGTH;
    }

    static Integer parseInt(String s) {
        try {
            return Integer.valueOf(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static BigDecimal parseNumber(String s) {
        try {
            return new BigDecimal(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static LocalDate parseDate(String s) {
        try {
            return LocalDate.parse(s.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
