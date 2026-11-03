package me.nageshk.omnichannel.service.automation;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;
import me.nageshk.omnichannel.db.automation.ConditionNode;
import me.nageshk.omnichannel.db.automation.ConditionResult;

/** Evaluates a condition tree against the values of an event. */
public final class ConditionEvaluator {

    /** A leaf condition with its path in the tree, e.g. {@code rules[1].rules[0]}. */
    public record Leaf(String path, ConditionNode node) {
    }

    private ConditionEvaluator() {
    }

    /** The leaves of the tree, depth first. A leaf root has the empty path. */
    public static List<Leaf> leaves(ConditionNode root) {
        List<Leaf> out = new ArrayList<>();
        if (root != null) {
            collect(root, "", out);
        }
        return out;
    }

    private static void collect(ConditionNode node, String path, List<Leaf> out) {
        if (!node.isGroup()) {
            out.add(new Leaf(path, node));
            return;
        }
        for (int i = 0; i < node.rules().size(); i++) {
            collect(node.rules().get(i), (path.isEmpty() ? "" : path + ".") + "rules[" + i + "]", out);
        }
    }

    public static ConditionResult check(Leaf leaf, Map<String, Object> values) {
        ConditionNode n = leaf.node();
        Object actual = values.get(n.field());
        return new ConditionResult(leaf.path(), n.field(), n.operator(), n.value(), actual,
                test(n.operator(), n.value(), actual));
    }

    /** Combines already-evaluated leaves (by path) through the groups of the tree. */
    public static boolean combine(ConditionNode root, Map<String, Boolean> passedByPath) {
        return fold(root, "", path -> Boolean.TRUE.equals(passedByPath.get(path)));
    }

    public static boolean matches(ConditionNode root, Map<String, Object> values) {
        if (root == null) {
            return true;
        }
        Map<String, Boolean> passed = new java.util.HashMap<>();
        for (Leaf l : leaves(root)) {
            passed.put(l.path(), check(l, values).passed());
        }
        return combine(root, passed);
    }

    private static boolean fold(ConditionNode node, String path, Predicate<String> leafPassed) {
        if (node == null) {
            return true;
        }
        if (!node.isGroup()) {
            return leafPassed.test(path);
        }
        if (node.rules().isEmpty()) {
            return true;
        }
        boolean any = "or".equals(node.combinator());
        boolean result = !any;
        for (int i = 0; i < node.rules().size(); i++) {
            String child = (path.isEmpty() ? "" : path + ".") + "rules[" + i + "]";
            boolean r = fold(node.rules().get(i), child, leafPassed);
            result = any ? result || r : result && r;
        }
        return Boolean.TRUE.equals(node.not()) ? !result : result;
    }

    private static boolean test(String operator, Object expected, Object actual) {
        Operator op = Operator.of(operator).orElse(null);
        if (op == null) {
            return false;
        }
        switch (op) {
            case IS_EMPTY:
                return isEmpty(actual);
            case IS_NOT_EMPTY:
                return !isEmpty(actual);
            case IS_ONE_OF:
                if (!(expected instanceof Collection<?> options)) {
                    return false;
                }
                String a = text(actual);
                return options.stream().anyMatch(o -> a.equals(text(o)));
            case IS:
                return equal(expected, actual);
            case IS_NOT:
                return !equal(expected, actual);
            case CONTAINS:
                return !text(actual).isEmpty() && text(actual).contains(text(expected));
            case NOT_CONTAINS:
                return text(actual).isEmpty() || !text(actual).contains(text(expected));
            case STARTS_WITH:
                return !text(actual).isEmpty() && text(actual).startsWith(text(expected));
            case ENDS_WITH:
                return !text(actual).isEmpty() && text(actual).endsWith(text(expected));
            case BETWEEN:
                if (!(expected instanceof List<?> range) || range.size() != 2) {
                    return false;
                }
                BigDecimal low = number(range.get(0));
                BigDecimal high = number(range.get(1));
                BigDecimal v = number(actual);
                return v != null && low != null && high != null && v.compareTo(low) >= 0 && v.compareTo(high) <= 0;
            default:
                BigDecimal x = number(actual);
                BigDecimal y = number(expected);
                if (x == null || y == null) {
                    return false;
                }
                int c = x.compareTo(y);
                return switch (op) {
                    case GREATER_THAN -> c > 0;
                    case LESS_THAN -> c < 0;
                    case AT_LEAST -> c >= 0;
                    default -> c <= 0;
                };
        }
    }

    private static boolean equal(Object expected, Object actual) {
        if (actual instanceof Boolean || expected instanceof Boolean) {
            return actual != null && expected != null
                    && Boolean.parseBoolean(actual.toString()) == Boolean.parseBoolean(expected.toString());
        }
        if (actual instanceof Number) {
            BigDecimal x = number(actual);
            BigDecimal y = number(expected);
            return y != null && x.compareTo(y) == 0;
        }
        return text(actual).equals(text(expected));
    }

    private static boolean isEmpty(Object actual) {
        return actual == null || text(actual).isEmpty();
    }

    private static String text(Object o) {
        return o == null ? "" : o.toString().trim().toLowerCase(Locale.ROOT);
    }

    private static BigDecimal number(Object o) {
        if (o instanceof BigDecimal b) {
            return b;
        }
        if (o instanceof Number || o instanceof String) {
            try {
                return new BigDecimal(o.toString().trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
}
