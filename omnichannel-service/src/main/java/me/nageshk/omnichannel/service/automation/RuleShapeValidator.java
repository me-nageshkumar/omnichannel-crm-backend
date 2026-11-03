package me.nageshk.omnichannel.service.automation;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import me.nageshk.omnichannel.db.automation.AutomationAction;
import me.nageshk.omnichannel.db.automation.AutomationActionType;
import me.nageshk.omnichannel.db.automation.AutomationEventKind;
import me.nageshk.omnichannel.db.automation.ConditionNode;

/** Checks the shape of a rule request: sizes, fields, operators and values. Whether named things exist is checked elsewhere. */
public final class RuleShapeValidator {

    private static final int MAX_GROUP_LEVELS = 3;
    private static final int MAX_CONDITIONS = 30;

    private RuleShapeValidator() {
    }

    /** Field key to one sentence, in the order found; empty when the shape is fine. */
    public static Map<String, String> check(RuleRequest r) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (r.name() == null || r.name().isBlank()) {
            errors.put("name", "is required");
        } else if (r.name().length() > 100) {
            errors.put("name", "must be at most 100 characters");
        }
        if (r.description() != null && r.description().length() > 500) {
            errors.put("description", "must be at most 500 characters");
        }
        if (r.event() == null) {
            errors.put("event", "is required");
        }
        if (r.priority() != null && (r.priority() < 0 || r.priority() > 9999)) {
            errors.put("priority", "must be a whole number from 0 to 9999");
        }
        checkConditions(r, errors);
        checkActions(r, errors);
        return errors;
    }

    private static void checkConditions(RuleRequest r, Map<String, String> errors) {
        ConditionNode root = r.conditions();
        if (root == null) {
            return;
        }
        if (depth(root) > MAX_GROUP_LEVELS) {
            errors.put("conditions", "at most 3 levels of groups");
            return;
        }
        if (ConditionEvaluator.leaves(root).size() > MAX_CONDITIONS) {
            errors.put("conditions", "at most 30 conditions");
            return;
        }
        walk(root, "conditions", r.event(), errors);
    }

    private static int depth(ConditionNode n) {
        if (!n.isGroup()) {
            return 0;
        }
        return 1 + n.rules().stream().mapToInt(RuleShapeValidator::depth).max().orElse(0);
    }

    private static void walk(ConditionNode n, String path, AutomationEventKind event, Map<String, String> errors) {
        if (n == null) {
            return;
        }
        if (n.isGroup()) {
            if (!"and".equals(n.combinator()) && !"or".equals(n.combinator())) {
                errors.put(path + ".combinator", "must be and or or");
            }
            for (int i = 0; i < n.rules().size(); i++) {
                walk(n.rules().get(i), path + ".rules[" + i + "]", event, errors);
            }
            return;
        }
        if (event == null) {
            return;
        }
        var spec = ConditionCatalog.field(event, n.field());
        if (spec.isEmpty()) {
            errors.put(path + ".field", "is not a field of this event");
            return;
        }
        Operator op = Operator.of(n.operator()).orElse(null);
        if (op == null || !spec.get().operators().contains(op)) {
            errors.put(path + ".operator", "is not offered for this field");
            return;
        }
        String problem = valueProblem(spec.get().type(), op, n.value());
        if (problem != null) {
            errors.put(path + ".value", problem);
        }
    }

    private static String valueProblem(ConditionCatalog.FieldType type, Operator op, Object v) {
        if (op == Operator.IS_EMPTY || op == Operator.IS_NOT_EMPTY) {
            return null;
        }
        if (v == null || (v instanceof String s && s.isBlank())) {
            return "is required";
        }
        switch (type) {
            case BOOLEAN:
                return v instanceof Boolean ? null : "must be true or false";
            case NUMBER:
                if (op == Operator.BETWEEN) {
                    return v instanceof List<?> l && l.size() == 2 && isNumber(l.get(0)) && isNumber(l.get(1)) ? null
                            : "must be two numbers, low then high";
                }
                return isNumber(v) ? null : "must be a number";
            default:
                if (op == Operator.IS_ONE_OF) {
                    return v instanceof List<?> l && !l.isEmpty() ? null : "choose at least one";
                }
                return null;
        }
    }

    private static boolean isNumber(Object o) {
        if (o instanceof Number) {
            return true;
        }
        if (o instanceof String s) {
            try {
                new BigDecimal(s.trim());
                return true;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return false;
    }

    private static void checkActions(RuleRequest r, Map<String, String> errors) {
        if (r.actions() == null) {
            return;
        }
        for (int i = 0; i < r.actions().size(); i++) {
            AutomationAction a = r.actions().get(i);
            String at = "actions[" + i + "]";
            if (a == null || a.type() == null) {
                errors.put(at + ".type", "is required");
                continue;
            }
            if (a.type() == AutomationActionType.ADD_TO_CRM && r.event() == AutomationEventKind.DEAL_LANE_CHANGED) {
                errors.put(at + ".type", "Add to CRM works on incoming messages only");
            }
            if (a.type() == AutomationActionType.ASSIGN_CHAT && a.mode() == null) {
                errors.put(at + ".mode", "is required");
            }
            if (a.dealTitle() != null && a.dealTitle().length() > 200) {
                errors.put(at + ".dealTitle", "must be at most 200 characters");
            }
        }
    }
}
